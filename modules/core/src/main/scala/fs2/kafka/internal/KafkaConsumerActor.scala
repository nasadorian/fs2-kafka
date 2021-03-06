/*
 * Copyright 2018-2020 OVO Energy Limited
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package fs2.kafka.internal

import cats.data.{Chain, NonEmptyList, NonEmptySet, NonEmptyVector, StateT}
import cats.effect.{ConcurrentEffect, IO, Timer}
import cats.effect.concurrent.{Deferred, Ref}
import cats.implicits._
import fs2.Chunk
import fs2.concurrent.Queue
import fs2.kafka._
import fs2.kafka.internal.converters.collection._
import fs2.kafka.internal.instances._
import fs2.kafka.internal.KafkaConsumerActor._
import fs2.kafka.internal.LogEntry._
import fs2.kafka.internal.syntax._
import java.time.Duration
import java.util
import java.util.regex.Pattern

import org.apache.kafka.clients.consumer.{
  ConsumerConfig,
  ConsumerRebalanceListener,
  OffsetAndMetadata,
  OffsetCommitCallback
}
import org.apache.kafka.common.TopicPartition

import scala.collection.immutable.SortedSet

/**
  * [[KafkaConsumerActor]] wraps a Java `KafkaConsumer` and works similar to
  * a traditional actor, in the sense that it receives requests one at-a-time
  * via a queue, which are received as calls to the `handle` function. `Poll`
  * requests are scheduled at a fixed interval and, when handled, calls the
  * `KafkaConsumer#poll` function, allowing the Java consumer to perform
  * necessary background functions, and to return fetched records.<br>
  * <br>
  * The actor receives `Fetch` requests for topic-partitions for which there
  * is demand. The actor then attempts to fetch records for topic-partitions
  * where there is a `Fetch` request. For topic-partitions where there is no
  * request, no attempt to fetch records is made. This effectively enables
  * backpressure, as long as `Fetch` requests are only issued when there
  * is more demand.
  */
private[kafka] final class KafkaConsumerActor[F[_], K, V](
  settings: ConsumerSettings[F, K, V],
  keyDeserializer: Deserializer[F, K],
  valueDeserializer: Deserializer[F, V],
  ref: Ref[F, State[F, K, V]],
  requests: Queue[F, Request[F, K, V]],
  withConsumer: WithConsumer[F]
)(
  implicit F: ConcurrentEffect[F],
  logging: Logging[F],
  jitter: Jitter[F],
  timer: Timer[F]
) {
  import logging._

  private[this] type ConsumerRecords =
    Map[TopicPartition, NonEmptyVector[CommittableConsumerRecord[F, K, V]]]

  private[this] val consumerGroupId: Option[String] =
    settings.properties.get(ConsumerConfig.GROUP_ID_CONFIG)

  private[this] val consumerRebalanceListener: ConsumerRebalanceListener =
    new ConsumerRebalanceListener {
      override def onPartitionsRevoked(partitions: util.Collection[TopicPartition]): Unit =
        F.toIO(revoked(partitions.toSortedSet)).unsafeRunSync()

      override def onPartitionsAssigned(partitions: util.Collection[TopicPartition]): Unit =
        F.toIO(assigned(partitions.toSortedSet)).unsafeRunSync()
    }

  private[this] def subscribe(
    topics: NonEmptyList[String],
    deferred: Deferred[F, Either[Throwable, Unit]]
  ): F[Unit] = {
    val subscribe =
      withConsumer { consumer =>
        F.delay {
          consumer.subscribe(
            topics.toList.asJava,
            consumerRebalanceListener
          )
        }.attempt
      }

    subscribe
      .flatTap {
        case Left(_) => F.unit
        case Right(_) =>
          ref
            .updateAndGet(_.asSubscribed)
            .log(SubscribedTopics(topics, _))
      }
      .flatMap(deferred.complete)
  }

  private[this] def subscribe(
    pattern: Pattern,
    deferred: Deferred[F, Either[Throwable, Unit]]
  ): F[Unit] = {
    val subscribe =
      withConsumer { consumer =>
        F.delay {
          consumer.subscribe(
            pattern,
            consumerRebalanceListener
          )
        }.attempt
      }

    subscribe
      .flatTap {
        case Left(_) => F.unit
        case Right(_) =>
          ref
            .updateAndGet(_.asSubscribed)
            .log(SubscribedPattern(pattern, _))
      }
      .flatMap(deferred.complete)
  }

  private[this] def unsubscribe(
    deferred: Deferred[F, Either[Throwable, Unit]]
  ): F[Unit] = {
    val unsubscribe =
      withConsumer { consumer =>
        F.delay {
          consumer.unsubscribe()
        }.attempt
      }

    unsubscribe
      .flatTap {
        case Left(_) => F.unit
        case Right(_) =>
          ref
            .updateAndGet(_.asUnsubscribed)
            .log(Unsubscribed(_))
      }
      .flatMap(deferred.complete)
  }

  private[this] def assign(
    partitions: NonEmptySet[TopicPartition],
    deferred: Deferred[F, Either[Throwable, Unit]]
  ): F[Unit] = {
    val assign =
      withConsumer { consumer =>
        F.delay {
          consumer.assign(
            partitions.toList.asJava
          )
        }.attempt
      }

    assign
      .flatTap {
        case Left(_) => F.unit
        case Right(_) =>
          ref
            .updateAndGet(_.asSubscribed)
            .log(ManuallyAssignedPartitions(partitions, _))
      }
      .flatMap(deferred.complete)
  }

  private[this] def fetch(
    partition: TopicPartition,
    streamId: StreamId,
    partitionStreamId: PartitionStreamId,
    deferred: Deferred[F, (Chunk[CommittableConsumerRecord[F, K, V]], FetchCompletedReason)]
  ): F[Unit] = {
    val assigned =
      withConsumer { consumer =>
        F.delay(consumer.assignment.contains(partition))
      }

    def storeFetch =
      ref
        .modify { state =>
          val (newState, oldFetch) =
            state.withFetch(partition, streamId, partitionStreamId, deferred)
          (newState, (newState, oldFetch))
        }
        .flatMap {
          case (newState, oldFetches) =>
            log(StoredFetch(partition, deferred, newState)) >>
              oldFetches.traverse_ { fetch =>
                fetch.completeRevoked(Chunk.empty) >>
                  log(RevokedPreviousFetch(partition, streamId))
              }
        }

    def completeRevoked =
      deferred.complete((Chunk.empty, FetchCompletedReason.TopicPartitionRevoked))

    assigned.ifM(storeFetch, completeRevoked)
  }

  private[this] def commitAsync(request: Request.Commit[F, K, V]): F[Unit] = {
    val commit =
      withConsumer { consumer =>
        F.delay {
          consumer.commitAsync(
            request.offsets.asJava,
            new OffsetCommitCallback {
              override def onComplete(
                offsets: util.Map[TopicPartition, OffsetAndMetadata],
                exception: Exception
              ): Unit = {
                val result = Option(exception).toLeft(())
                val complete = request.deferred.complete(result)
                F.runAsync(complete)(_ => IO.unit).unsafeRunSync()
              }
            }
          )
        }.attempt
      }

    commit.flatMap {
      case Right(()) => F.unit
      case Left(e)   => request.deferred.complete(Left(e))
    }
  }

  private[this] def commit(request: Request.Commit[F, K, V]): F[Unit] = {
    ref
      .modify { state =>
        if (state.rebalancing) {
          val newState = state.withPendingCommit(request)
          (newState, Some(StoredPendingCommit(request, newState)))
        } else (state, None)
      }
      .flatMap {
        case Some(log) => logging.log(log)
        case None      => commitAsync(request)
      }
  }

  private[this] def assigned(assigned: SortedSet[TopicPartition]): F[Unit] =
    ref
      .updateAndGet(_.withRebalancing(false))
      .flatMap { state =>
        log(AssignedPartitions(assigned, state)) >>
          state.onRebalances.foldLeft(F.unit)(_ >> _.onAssigned(assigned))
      }

  private[this] def revoked(revoked: SortedSet[TopicPartition]): F[Unit] = {
    def withState[A] = StateT.apply[Id, State[F, K, V], A](_)

    def completeWithRecords(withRecords: Set[TopicPartition]) = withState { st =>
      if (withRecords.nonEmpty) {
        val newState = st.withoutFetchesAndRecords(withRecords)

        val action = st.fetches.filterKeysStrictList(withRecords).traverse {
          case (partition, partitionFetches) =>
            val records = Chunk.vector(st.records(partition).toVector)
            partitionFetches.values.toList.traverse(_.completeRevoked(records))
        } >> logging.log(
          RevokedFetchesWithRecords(st.records.filterKeysStrict(withRecords), newState)
        )

        (newState, action)
      } else (st, F.unit)
    }

    def completeWithoutRecords(withoutRecords: SortedSet[TopicPartition]) = withState { st =>
      if (withoutRecords.nonEmpty) {
        val newState = st.withoutFetches(withoutRecords)

        val action = st.fetches
          .filterKeysStrictValuesList(withoutRecords)
          .traverse(_.values.toList.traverse(_.completeRevoked(Chunk.empty))) >>
          logging.log(RevokedFetchesWithoutRecords(withoutRecords, newState))

        (newState, action)
      } else (st, F.unit)
    }

    def removeRevokedRecords(revokedNonFetches: SortedSet[TopicPartition]) = withState { st =>
      if (revokedNonFetches.nonEmpty) {
        val revokedRecords = st.records.filterKeysStrict(revokedNonFetches)

        if (revokedRecords.nonEmpty) {
          val newState = st.withoutRecords(revokedRecords.keySet)

          val action = logging.log(RemovedRevokedRecords(revokedRecords, newState))

          (newState, action)
        } else (st, F.unit)
      } else (st, F.unit)
    }

    ref
      .modify { state =>
        val withRebalancing = state.withRebalancing(true)

        val fetches = withRebalancing.fetches.keySetStrict
        val records = withRebalancing.records.keySetStrict

        val revokedFetches = revoked intersect fetches
        val revokedNonFetches = revoked diff fetches

        val withRecords = records intersect revokedFetches
        val withoutRecords = revokedFetches diff records

        (for {
          completeWithRecords <- completeWithRecords(withRecords)
          completeWithoutRecords <- completeWithoutRecords(withoutRecords)
          removeRevokedRecords <- removeRevokedRecords(revokedNonFetches)
        } yield RevokedResult(
          logRevoked = logging.log(RevokedPartitions(revoked, withRebalancing)),
          completeWithRecords = completeWithRecords,
          completeWithoutRecords = completeWithoutRecords,
          removeRevokedRecords = removeRevokedRecords,
          onRebalances = withRebalancing.onRebalances
        )).run(withRebalancing)
      }
      .flatMap { res =>
        val onRevoked =
          res.onRebalances.foldLeft(F.unit)(_ >> _.onRevoked(revoked))

        res.logRevoked >>
          res.completeWithRecords >>
          res.completeWithoutRecords >>
          res.removeRevokedRecords >>
          onRevoked
      }
  }

  private[this] def assignment(
    deferred: Deferred[F, Either[Throwable, SortedSet[TopicPartition]]],
    onRebalance: Option[OnRebalance[F, K, V]]
  ): F[Unit] = {
    def resolveDeferred(subscribed: Boolean): F[Unit] = {
      val result = if (subscribed) withConsumer { consumer =>
        F.delay(Right(consumer.assignment.toSortedSet))
      } else F.pure(Left(NotSubscribedException()))

      result.flatMap(deferred.complete)
    }

    onRebalance match {
      case Some(on) =>
        ref.updateAndGet(_.withOnRebalance(on).asStreaming).flatMap { newState =>
          resolveDeferred(newState.subscribed) >> logging.log(StoredOnRebalance(on, newState))
        }

      case None =>
        ref.updateAndGet(_.asStreaming).flatMap { newState =>
          resolveDeferred(newState.subscribed)
        }
    }
  }

  private[this] val offsetCommit: Map[TopicPartition, OffsetAndMetadata] => F[Unit] =
    offsets => {
      val commit =
        Deferred[F, Either[Throwable, Unit]].flatMap { deferred =>
          requests.enqueue1(Request.Commit(offsets, deferred)) >>
            F.race(timer.sleep(settings.commitTimeout), deferred.get.rethrow)
              .flatMap {
                case Right(_) => F.unit
                case Left(_) =>
                  F.raiseError[Unit] {
                    CommitTimeoutException(
                      settings.commitTimeout,
                      offsets
                    )
                  }
              }
        }

      commit.handleErrorWith {
        settings.commitRecovery
          .recoverCommitWith(offsets, commit)
      }
    }

  private[this] def committableConsumerRecord(
    record: ConsumerRecord[K, V],
    partition: TopicPartition
  ): CommittableConsumerRecord[F, K, V] =
    CommittableConsumerRecord(
      record = record,
      offset = CommittableOffset(
        topicPartition = partition,
        consumerGroupId = consumerGroupId,
        offsetAndMetadata = new OffsetAndMetadata(
          record.offset + 1L,
          settings.recordMetadata(record)
        ),
        commit = offsetCommit
      )
    )

  private[this] def records(batch: KafkaByteConsumerRecords): F[ConsumerRecords] =
    batch.partitions.toVector
      .traverse { partition =>
        NonEmptyVector
          .fromVectorUnsafe(batch.records(partition).toVector)
          .traverse { record =>
            ConsumerRecord
              .fromJava(record, keyDeserializer, valueDeserializer)
              .map(committableConsumerRecord(_, partition))
          }
          .map((partition, _))
      }
      .map(_.toMap)

  private[this] val pollTimeout: Duration =
    settings.pollTimeout.asJava

  private[this] val poll: F[Unit] = {
    def pollConsumer(state: State[F, K, V]): F[ConsumerRecords] =
      withConsumer { consumer =>
        F.suspend {
          val assigned = consumer.assignment.toSet
          val requested = state.fetches.keySetStrict
          val available = state.records.keySetStrict

          val resume = (requested intersect assigned) diff available
          val pause = assigned diff resume

          if (pause.nonEmpty)
            consumer.pause(pause.asJava)

          if (resume.nonEmpty)
            consumer.resume(resume.asJava)

          records(consumer.poll(pollTimeout))
        }
      }

    def handlePoll(newRecords: ConsumerRecords, initialRebalancing: Boolean): F[Unit] = {
      def handleBatch(
        state: State[F, K, V],
        pendingCommits: Option[HandlePollResult.PendingCommits]
      ) = {
        if (state.fetches.isEmpty) {
          if (newRecords.isEmpty) {
            (state, HandlePollResult.StateNotChanged(pendingCommits))
          } else {
            val newState = state.withRecords(newRecords)
            (newState, HandlePollResult.Stored(StoredRecords(newRecords, newState), pendingCommits))
          }
        } else {
          val allRecords = state.records combine newRecords

          if (allRecords.nonEmpty) {
            val requested = state.fetches.keySetStrict

            val canBeCompleted = allRecords.keySetStrict intersect requested
            val canBeStored = newRecords.keySetStrict diff canBeCompleted

            def completeFetches: F[Unit] = {
              state.fetches.filterKeysStrictList(canBeCompleted).traverse_ {
                case (partition, fetches) =>
                  val records = Chunk.vector(allRecords(partition).toVector)
                  fetches.values.toList.traverse_(_.completeRecords(records))
              }
            }

            (canBeCompleted.nonEmpty, canBeStored.nonEmpty) match {
              case (true, true) =>
                val storeRecords = newRecords.filterKeysStrict(canBeStored)
                val newState =
                  state.withoutFetchesAndRecords(canBeCompleted).withRecords(storeRecords)
                (
                  newState,
                  HandlePollResult.CompletedAndStored(
                    completeFetches = completeFetches,
                    completedLog = CompletedFetchesWithRecords(
                      allRecords.filterKeysStrict(canBeCompleted),
                      newState
                    ),
                    storedLog = StoredRecords(storeRecords, newState),
                    pendingCommits = pendingCommits
                  )
                )

              case (true, false) =>
                val newState = state.withoutFetchesAndRecords(canBeCompleted)
                (
                  newState,
                  HandlePollResult.Completed(
                    completeFetches = completeFetches,
                    log = CompletedFetchesWithRecords(
                      allRecords.filterKeysStrict(canBeCompleted),
                      newState
                    ),
                    pendingCommits = pendingCommits
                  )
                )

              case (false, true) =>
                val storeRecords = newRecords.filterKeysStrict(canBeStored)
                val newState = state.withRecords(storeRecords)
                (
                  newState,
                  HandlePollResult.Stored(StoredRecords(storeRecords, newState), pendingCommits)
                )

              case (false, false) =>
                (state, HandlePollResult.StateNotChanged(pendingCommits))
            }
          } else {
            (state, HandlePollResult.StateNotChanged(pendingCommits))
          }
        }
      }

      def handlePendingCommits(state: State[F, K, V]) = {
        val currentRebalancing = state.rebalancing

        if (initialRebalancing && !currentRebalancing && state.pendingCommits.nonEmpty) {
          val newState = state.withoutPendingCommits
          (
            newState,
            Some(
              HandlePollResult.PendingCommits(
                commits = state.pendingCommits,
                log = CommittedPendingCommits(state.pendingCommits, newState)
              )
            )
          )
        } else (state, None)
      }

      ref
        .modify { state =>
          val (stateWithoutPendingCommits, pendingCommits) = handlePendingCommits(state)
          handleBatch(stateWithoutPendingCommits, pendingCommits)
        }
        .flatMap { result =>
          (result match {
            case HandlePollResult.StateNotChanged(_) =>
              F.unit
            case HandlePollResult.Stored(log, _) =>
              logging.log(log)
            case HandlePollResult.Completed(completeFetches, log, _) =>
              completeFetches >> logging.log(log)
            case HandlePollResult.CompletedAndStored(completeFetches, completedLog, storedLog, _) =>
              completeFetches >> logging.log(completedLog) >> logging.log(storedLog)
          }) >> result.pendingCommits.traverse_(_.commit)

        }
    }

    ref.get.flatMap { state =>
      if (state.subscribed && state.streaming) {
        val initialRebalancing = state.rebalancing
        pollConsumer(state).flatMap(handlePoll(_, initialRebalancing))
      } else F.unit
    }
  }

  def handle(request: Request[F, K, V]): F[Unit] =
    request match {
      case Request.Assignment(deferred, onRebalance)   => assignment(deferred, onRebalance)
      case Request.Poll()                              => poll
      case Request.SubscribeTopics(topics, deferred)   => subscribe(topics, deferred)
      case Request.Assign(partitions, deferred)        => assign(partitions, deferred)
      case Request.SubscribePattern(pattern, deferred) => subscribe(pattern, deferred)
      case Request.Unsubscribe(deferred)               => unsubscribe(deferred)
      case Request.Fetch(partition, streamId, partitionStreamId, deferred) =>
        fetch(partition, streamId, partitionStreamId, deferred)
      case request @ Request.Commit(_, _) => commit(request)
    }

  private[this] case class RevokedResult(
    logRevoked: F[Unit],
    completeWithRecords: F[Unit],
    completeWithoutRecords: F[Unit],
    removeRevokedRecords: F[Unit],
    onRebalances: Chain[OnRebalance[F, K, V]]
  )

  private[this] sealed trait HandlePollResult {
    def pendingCommits: Option[HandlePollResult.PendingCommits]
  }
  private[this] object HandlePollResult {
    case class PendingCommits(
      commits: Chain[Request.Commit[F, K, V]],
      log: CommittedPendingCommits[F, K, V]
    ) {
      def commit: F[Unit] = {
        commits.foldLeft(F.unit)(_ >> commitAsync(_)) >> logging.log(log)
      }
    }

    case class StateNotChanged(pendingCommits: Option[PendingCommits]) extends HandlePollResult

    case class Stored(
      log: LogEntry.StoredRecords[F, K, V],
      pendingCommits: Option[PendingCommits]
    ) extends HandlePollResult

    case class Completed(
      completeFetches: F[Unit],
      log: LogEntry.CompletedFetchesWithRecords[F, K, V],
      pendingCommits: Option[PendingCommits]
    ) extends HandlePollResult

    case class CompletedAndStored(
      completeFetches: F[Unit],
      completedLog: LogEntry.CompletedFetchesWithRecords[F, K, V],
      storedLog: LogEntry.StoredRecords[F, K, V],
      pendingCommits: Option[PendingCommits]
    ) extends HandlePollResult
  }
}

private[kafka] object KafkaConsumerActor {
  final case class FetchRequest[F[_], K, V](
    deferred: Deferred[F, (Chunk[CommittableConsumerRecord[F, K, V]], FetchCompletedReason)]
  ) {
    def completeRevoked(chunk: Chunk[CommittableConsumerRecord[F, K, V]]): F[Unit] =
      deferred.complete((chunk, FetchCompletedReason.TopicPartitionRevoked))

    def completeRecords(chunk: Chunk[CommittableConsumerRecord[F, K, V]]): F[Unit] =
      deferred.complete((chunk, FetchCompletedReason.FetchedRecords))

    override def toString: String =
      "FetchRequest$" + System.identityHashCode(this)
  }

  type StreamId = Int
  type PartitionStreamId = Int

  final case class State[F[_], K, V](
    fetches: Map[TopicPartition, Map[StreamId, FetchRequest[F, K, V]]],
    partitionStreamIds: Map[TopicPartition, PartitionStreamId],
    records: Map[TopicPartition, NonEmptyVector[CommittableConsumerRecord[F, K, V]]],
    pendingCommits: Chain[Request.Commit[F, K, V]],
    onRebalances: Chain[OnRebalance[F, K, V]],
    rebalancing: Boolean,
    subscribed: Boolean,
    streaming: Boolean
  ) {
    def withOnRebalance(onRebalance: OnRebalance[F, K, V]): State[F, K, V] =
      copy(onRebalances = onRebalances append onRebalance)

    /**
      * @return (new-state, old-fetches-to-revoke)
      */
    def withFetch(
      partition: TopicPartition,
      streamId: StreamId,
      partitionStreamId: PartitionStreamId,
      deferred: Deferred[F, (Chunk[CommittableConsumerRecord[F, K, V]], FetchCompletedReason)]
    ): (State[F, K, V], List[FetchRequest[F, K, V]]) = {
      val newFetchRequest =
        FetchRequest(deferred)

      val oldPartitionFetches =
        fetches.getOrElse(partition, Map.empty)

      val oldPartitionFetch =
        oldPartitionFetches.get(streamId)

      val oldPartitionStreamId =
        partitionStreamIds.getOrElse(partition, 0)

      val hasMoreRecentPartitionStreamIds =
        oldPartitionStreamId > partitionStreamId

      val newFetches =
        fetches.updated(partition, {
          if (hasMoreRecentPartitionStreamIds) oldPartitionFetches - streamId
          else oldPartitionFetches.updated(streamId, newFetchRequest)
        })

      val newPartitionStreamIds =
        partitionStreamIds.updated(partition, oldPartitionStreamId max partitionStreamId)

      val fetchesToRevoke =
        if (hasMoreRecentPartitionStreamIds)
          newFetchRequest :: oldPartitionFetch.toList
        else oldPartitionFetch.toList

      (
        copy(fetches = newFetches, partitionStreamIds = newPartitionStreamIds),
        fetchesToRevoke
      )
    }

    def withoutFetches(partitions: Set[TopicPartition]): State[F, K, V] =
      copy(
        fetches = fetches.filterKeysStrict(!partitions.contains(_)),
        partitionStreamIds = partitionStreamIds.filterKeysStrict(!partitions.contains(_))
      )

    def withRecords(
      records: Map[TopicPartition, NonEmptyVector[CommittableConsumerRecord[F, K, V]]]
    ): State[F, K, V] =
      copy(records = this.records combine records)

    def withoutFetchesAndRecords(partitions: Set[TopicPartition]): State[F, K, V] =
      copy(
        fetches = fetches.filterKeysStrict(!partitions.contains(_)),
        partitionStreamIds = partitionStreamIds.filterKeysStrict(!partitions.contains(_)),
        records = records.filterKeysStrict(!partitions.contains(_))
      )

    def withoutRecords(partitions: Set[TopicPartition]): State[F, K, V] =
      copy(records = records.filterKeysStrict(!partitions.contains(_)))

    def withPendingCommit(pendingCommit: Request.Commit[F, K, V]): State[F, K, V] =
      copy(pendingCommits = pendingCommits append pendingCommit)

    def withoutPendingCommits: State[F, K, V] =
      if (pendingCommits.isEmpty) this else copy(pendingCommits = Chain.empty)

    def withRebalancing(rebalancing: Boolean): State[F, K, V] =
      if (this.rebalancing == rebalancing) this else copy(rebalancing = rebalancing)

    def asSubscribed: State[F, K, V] =
      if (subscribed) this else copy(subscribed = true)

    def asUnsubscribed: State[F, K, V] =
      if (!subscribed) this else copy(subscribed = false)

    def asStreaming: State[F, K, V] =
      if (streaming) this else copy(streaming = true)

    override def toString: String = {
      val fetchesString =
        fetches.toList
          .sortBy { case (tp, _) => tp }
          .mkStringAppend {
            case (append, (tp, fs)) =>
              append(tp.toString)
              append(" -> ")
              append(fs.mkString("[", ", ", "]"))
          }("", ", ", "")

      val partitionStreamIdsString =
        partitionStreamIds.toList
          .sortBy { case (tp, _) => tp }
          .mkStringAppend {
            case (append, (tp, id)) =>
              append(tp.toString)
              append(" -> ")
              append(id.toString)
          }("", ", ", "")

      s"State(fetches = Map($fetchesString), partitionStreamIds = Map($partitionStreamIdsString), records = Map(${recordsString(records)}), pendingCommits = $pendingCommits, onRebalances = $onRebalances, rebalancing = $rebalancing, subscribed = $subscribed, streaming = $streaming)"
    }
  }

  object State {
    def empty[F[_], K, V]: State[F, K, V] =
      State(
        fetches = Map.empty,
        partitionStreamIds = Map.empty,
        records = Map.empty,
        pendingCommits = Chain.empty,
        onRebalances = Chain.empty,
        rebalancing = false,
        subscribed = false,
        streaming = false
      )
  }

  sealed abstract class FetchCompletedReason {
    final def topicPartitionRevoked: Boolean = this match {
      case FetchCompletedReason.TopicPartitionRevoked => true
      case FetchCompletedReason.FetchedRecords        => false
    }
  }

  object FetchCompletedReason {
    case object TopicPartitionRevoked extends FetchCompletedReason

    case object FetchedRecords extends FetchCompletedReason
  }

  final case class OnRebalance[F[_], K, V](
    onAssigned: SortedSet[TopicPartition] => F[Unit],
    onRevoked: SortedSet[TopicPartition] => F[Unit]
  ) {
    override def toString: String =
      "OnRebalance$" + System.identityHashCode(this)
  }

  sealed abstract class Request[F[_], K, V]

  object Request {
    final case class Assignment[F[_], K, V](
      deferred: Deferred[F, Either[Throwable, SortedSet[TopicPartition]]],
      onRebalance: Option[OnRebalance[F, K, V]]
    ) extends Request[F, K, V]

    final case class Poll[F[_], K, V]() extends Request[F, K, V]

    private[this] val pollInstance: Poll[Nothing, Nothing, Nothing] =
      Poll[Nothing, Nothing, Nothing]()

    def poll[F[_], K, V]: Poll[F, K, V] =
      pollInstance.asInstanceOf[Poll[F, K, V]]

    final case class SubscribeTopics[F[_], K, V](
      topics: NonEmptyList[String],
      deferred: Deferred[F, Either[Throwable, Unit]]
    ) extends Request[F, K, V]

    final case class Assign[F[_], K, V](
      topicPartitions: NonEmptySet[TopicPartition],
      deferred: Deferred[F, Either[Throwable, Unit]]
    ) extends Request[F, K, V]

    final case class SubscribePattern[F[_], K, V](
      pattern: Pattern,
      deferred: Deferred[F, Either[Throwable, Unit]]
    ) extends Request[F, K, V]

    final case class Unsubscribe[F[_], K, V](
      deferred: Deferred[F, Either[Throwable, Unit]]
    ) extends Request[F, K, V]

    final case class Fetch[F[_], K, V](
      partition: TopicPartition,
      streamId: StreamId,
      partitionStreamId: PartitionStreamId,
      deferred: Deferred[F, (Chunk[CommittableConsumerRecord[F, K, V]], FetchCompletedReason)]
    ) extends Request[F, K, V]

    final case class Commit[F[_], K, V](
      offsets: Map[TopicPartition, OffsetAndMetadata],
      deferred: Deferred[F, Either[Throwable, Unit]]
    ) extends Request[F, K, V]
  }
}

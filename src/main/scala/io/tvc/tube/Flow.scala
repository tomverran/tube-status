package io.tvc.tube

import java.time.{Clock, Duration => JDuration}

import cats.effect.{Effect, Sync}
import cats.syntax.applicative._
import cats.syntax.functor._
import fs2.async.mutable.Queue
import fs2.{Pipe, Stream}
import io.tvc.tube.TflClient.Arrival

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.language.higherKinds

object Flow {

  case class SuspendedQueue[F[_]](q: Queue[F, DirectedInterval], run: F[Unit])

  /**
    * Grim function that lets us break out service intervals from an FS2 stream
    * by writing them into a mutable queue. This returns the queue + the stream to run to fill it
    * both wrapped in the effect type F
    */
  def queue[F[_] : Effect : Delay](
    client: TflClient[F],
    lineId: LineId,
    branch: Branch
  )(
    implicit
    clock: Clock,
    ec: ExecutionContext
  ): F[SuspendedQueue[F]] = {
    for {
      q <- Queue.circularBuffer[F, DirectedInterval](maxSize = 10)
    } yield SuspendedQueue(q, stream(client, lineId, branch).to(q.enqueue).compile.drain)
  }

  /**
    * Monitor the arrivals to the given line & branch
    * and calculate the interval between arrivals
    */
  def stream[F[_] : Sync : Delay](
    client: TflClient[F],
    lineId: LineId,
    branch: Branch
  )(
    implicit
    clock: Clock
  ): Stream[F, DirectedInterval] =
    Stream.repeatEval(client.nextArrival(lineId, branch).pure[F])
      .through(evalThrottled(2.minutes))
      .through(delayUntilArrival)
      .through(calculateInterval)
      .observe1(dur => Sync[F].delay(println(s"Interval is $dur")))

  /**
    * Given a stream containing a nested effect that emits a List
    * evaluate the effect and if the list is empty then sleep before retrying.
    * If the list is non empty its contents will be emitted as part of the stream.
    */
  def evalThrottled[F[_] : Sync : Delay, A](duration: FiniteDuration): Pipe[F, F[List[A]], A] =
    _.evalMap(identity).flatMap {
      case Nil => Delay[F].delayStream(duration).map(_ => List.empty[A])
      case a => Stream.eval[F, List[A]](a.pure[F])
    }.flatMap(Stream.emits(_))


  /**
    * Given a stream of arrivals
    * zip up two adjacent ones and calculate the time between them
    */
  def calculateInterval[F[_]](implicit clock: Clock): Pipe[F, Arrival, DirectedInterval] =
    _.mapAccumulate[Map[Direction, Arrival], Option[DirectedInterval]](Map.empty) {
      case (previous, arrival) =>
        (
          previous.updated(arrival.platformName, arrival),
          previous
            .get(arrival.platformName)
            .collect {
              case (p) if p.vehicleId != arrival.vehicleId =>
                val duration = JDuration.between(p.expectedArrival, arrival.expectedArrival)
                DirectedInterval(arrival.platformName, duration.getSeconds.seconds)
            }
        )
    }.map(_._2).unNone

  /**
    * Given an optional train arrival, sleep until it is scheduled to arrive
    */
  def delayUntilArrival[F[_] : Delay : Sync](implicit clock: Clock): Pipe[F, Arrival, Arrival] =
    stream =>
      for {
        arrival <- stream
        arrivalTime = arrival.expectedArrival.toInstant
        sleepTime = Math.max(JDuration.between(clock.instant, arrivalTime).getSeconds, 10).seconds
        _ <- Delay[F].delayStream(sleepTime)
      } yield arrival
}

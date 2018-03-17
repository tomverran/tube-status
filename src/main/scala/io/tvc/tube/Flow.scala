package io.tvc.tube

import java.time.{Clock, Duration => JDuration}

import cats.effect.{Effect, Sync}
import cats.instances.string._
import cats.syntax.applicative._
import fs2.Pipe
import io.tvc.tube.TflClient.Arrival
import fs2.Stream
import fs2.async.mutable.Queue

import scala.concurrent.duration._
import scala.language.higherKinds

object Flow {

  /**
    * Monitor the arrivals to the given line & branch
    * and calculate the interval between arrivals
    */
  def stream[F[_] : Sync : Delay](client: TflClient[F], lineId: LineId, branch: Branch)(implicit clock: Clock) =
    Stream.repeatEval(client.nextArrival(lineId, branch, Eastbound).pure[F])
      .evalMap(identity)
      .through(delayUntilArrival)
      .through(groupByTrainId)
      .through(calculateInterval)
      .observe1(dur => Sync[F].delay(println(s"Interval is ${dur.getSeconds / 60}")))

  /**
    * Given a stream of arrivals
    * zip up two adjacent ones and calculate the time between them
    */
  def calculateInterval[F[_]]: Pipe[F, Arrival, JDuration] = _.zipWithPrevious.collect {
    case (Some(prev), train) => JDuration.between(prev.expectedArrival, train.expectedArrival)
  }

  /**
    * Group up duplicate arrival information by train ID
    * and emit only the last bit of arrival info we got
    */
  def groupByTrainId[F[_] : Sync]: Pipe[F, Arrival, Arrival] =
    _.groupAdjacentBy(_.vehicleId).map(_._2.force.toList.last)

  /**
    * Given an optional train arrival
    * sleep for either 60 seconds (if there are no arrivals)
    * or until it is scheduled to arrive
    */
  def delayUntilArrival[F[_] : Delay : Sync](implicit clock: Clock): Pipe[F, Option[Arrival], Arrival] = stream =>
    (
      for {
        arrival <- stream
        arrivalTime = arrival.fold(clock.instant.plusSeconds(60))(_.expectedArrival.toInstant)
        sleepTime = Math.max(JDuration.between(clock.instant, arrivalTime).getSeconds, 10).seconds
        _ = println(s"$sleepTime seconds until the train arrives, sleeping")
        _ <- Stream.eval(().pure[F]).through(Delay[F].delay(sleepTime))
      } yield arrival
      ).unNone.map { a => println(s"Emitting $a") ; a }
}

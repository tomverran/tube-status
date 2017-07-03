package io.tvc.tube

import java.time.{Clock, ZonedDateTime}

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, Uri}
import akka.http.scaladsl.model.HttpMethods.GET
import akka.http.scaladsl.model.Uri.Query
import akka.stream.ActorMaterializer
import io.circe.Decoder

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import io.circe.generic.semiauto._
import io.circe.parser.decode

import scala.util.Try

class TflClient(config: ApiConfig)(implicit a: ActorSystem, m: ActorMaterializer, e: ExecutionContext) {

  private val http = Http()
  private val credentials = Query("app_id" -> config.appId, "app_key" -> config.appKey)
  private case class Arrival(lineId: String, platformName: String, expectedArrival: ZonedDateTime)
  private implicit val zdtDecode = Decoder.decodeString.emapTry(s => Try(ZonedDateTime.parse(s)))
  private implicit val decoder = deriveDecoder[Arrival]

  private def byDirection(arrivals: List[Arrival]): Map[Direction, List[Arrival]] =
    arrivals.groupBy(_.platformName.split(' ').headOption.flatMap(d => Direction.fromStringName(d.trim))).collect {
      case (Some(d), a@(_ :: _)) => (d, a)
    }

  private def average(l: List[FiniteDuration]): Option[FiniteDuration] =
    if (l.nonEmpty) Some(l.map(_.toSeconds).sum / l.length).map(_.seconds) else None

  private def arrivalsToIntervals(lineId: LineId, branchId: BranchId)(arrivals: List[Arrival])(implicit clock: Clock): ServiceInterval = {
    val parse: List[Arrival] => List[FiniteDuration] =
      _.filter(_.lineId == lineId.value)
      .sortBy(_.expectedArrival.toEpochSecond)
      .sliding(2)
      .flatMap {
        case h :: t :: Nil =>
          List((t.expectedArrival.toEpochSecond - h.expectedArrival.toEpochSecond).seconds)
        case _ =>
          List.empty
      }
      .toList

    val intervals = for {
      (dir, arrs) <- byDirection(arrivals).toList
      avg <- average(parse(arrs)).toList
    } yield DirectedInterval(dir, avg)

    ServiceInterval(ZonedDateTime.now(clock), lineId, branchId, intervals)
  }

  def departures(l: LineId, b: Branch)(implicit clock: Clock): Future[Either[io.circe.Error, ServiceInterval]] = {
    val uri = Uri(s"https://api.tfl.gov.uk/StopPoint/${b.stopId}/arrivals").withQuery(credentials)
    val req = HttpRequest(method = GET, uri = uri)
    for {
      resp <- http.singleRequest(req)
      body <- resp.entity.toStrict(1.second)
    } yield {
      val result = decode[List[Arrival]](body.data.decodeString("UTF-8"))
      result.map(arrivalsToIntervals(l, b.id))
    }
  }
}

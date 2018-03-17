package io.tvc.tube

import java.time.{Clock, ZonedDateTime}

import akka.http.scaladsl.HttpExt
import akka.http.scaladsl.model.HttpMethods.GET
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.{HttpRequest, Uri}
import akka.stream.Materializer
import cats.effect.IO
import io.circe.Decoder
import io.circe.generic.semiauto._
import io.circe.parser.decode
import io.tvc.tube.TflClient.Arrival

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.higherKinds
import scala.util.Try

trait TflClient[F[_]] {
  def nextArrival(l: LineId, b: Branch): F[List[Arrival]]
}

object TflClient {

  case class Arrival(vehicleId: String, lineId: String, platformName: Direction, expectedArrival: ZonedDateTime)
  private implicit val zdtDecode = Decoder.decodeString.emapTry(s => Try(ZonedDateTime.parse(s)))
  private implicit val decoder = deriveDecoder[Arrival]

  private implicit val dirDecode: Decoder[Direction] =
    Decoder.decodeString.emap { s =>
      (
        for {
          firstWord <- s.split(' ').headOption
          direction <- Direction.fromStringName(firstWord)
        } yield direction
      ).toRight(s"Couldn't find direction in $s")
    }

  /**
    * An instance of the TFL client algebra
    * using Akka HTTP until it can be removed
    */
  def akkaClient(config: ApiConfig, http: HttpExt)(implicit clock: Clock, mat: Materializer): TflClient[IO] =
    new TflClient[IO]  {

      private implicit val o: Ordering[ZonedDateTime] = (a, b) => a.toEpochSecond.compareTo(b.toEpochSecond)
      private val credentials = Query("app_id" -> config.appId, "app_key" -> config.appKey)
      private implicit val ec = mat.executionContext

      def nextArrival(l: LineId, b: Branch): IO[List[Arrival]] = {
        val uri = Uri(s"https://api.tfl.gov.uk/StopPoint/${b.stopId}/arrivals").withQuery(credentials)
        val req = HttpRequest(method = GET, uri = uri)

        IO.fromFuture(
          IO(
            for {
              resp <- http.singleRequest(req)
              body <- resp.entity.toStrict(10.seconds)
              string = body.data.decodeString("UTF-8")
              arrs <- decode[List[Arrival]](string).fold(Future.failed, Future.successful)
              _ = println(arrs)
            } yield arrs
              .filter(_.lineId == l.value)
              .sortBy(_.expectedArrival)
          )
        )
      }
    }
}

package io.tvc.tube

import java.time.Clock

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.{Encoder, Json}
import io.circe.generic.auto._
import io.circe.syntax._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object Routes {

  implicit val encodeFiniteDuration: Encoder[FiniteDuration] =
    Encoder.encodeLong.contramap[FiniteDuration](_.toSeconds)

  implicit val encodeDirection: Encoder[Direction] =
    Encoder.encodeString.contramap[Direction](_.id)

  implicit val encodeLineId: Encoder[LineId] =
    Encoder.encodeString.contramap[LineId](_.value)

  implicit val encodeBranchId: Encoder[BranchId] =
    Encoder.encodeString.contramap[BranchId](_.value)

  implicit val encodeLine: Encoder[Line] = Encoder.instance { c =>
    Json.obj(
      "id" -> c.id.asJson,
      "name" -> c.name.asJson,
      "bg" -> c.display.bg.asJson,
      "fg" -> c.display.fg.asJson
    )
  }

  def forLines(lines: List[Line])(implicit as: ActorSystem, mat: ActorMaterializer, clock: Clock, ec: ExecutionContext): Future[Http.ServerBinding] = {
    val route = get {
      path("api") {
        complete(Metrics.serviceLevels(lines).map(_.asJson))
      } ~ pathEndOrSingleSlash {
        getFromResource("index.html")
      }
    }

    Http().bindAndHandle(route, "0.0.0.0", 8080)
  }
}

package io.tvc.tube

import java.time.Clock
import java.util.concurrent.atomic.AtomicReference

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

class Routes(lines: List[Line])(implicit as: ActorSystem, mat: ActorMaterializer, clock: Clock, ec: ExecutionContext) {

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

  implicit val encodeLineStatus: Encoder[BranchStatus] = Encoder.instance { c =>
    val level: Json = c.level match {
      case Good(usually, currently) => Json.obj("level" -> "good".asJson, "usually" -> usually.asJson ,"currently" -> currently.asJson)
      case Bad(usually, currently) => Json.obj("level" -> "good".asJson, "usually" -> usually.asJson ,"currently" -> currently.asJson)
      case NoTrains => Json.obj("level" -> "unknown".asJson)
    }
    Json.obj(
     "name" -> c.name.fold(c.direction.id)(n => s"$n ${c.direction.id}").asJson
    ).deepMerge(level)
  }

  private val agent: AtomicReference[List[LineStatus]] = new AtomicReference(List.empty[LineStatus])
  as.scheduler.schedule(0.seconds, 5.minutes)(Metrics.serviceLevels(lines).foreach { l =>
    println("Refreshing agent")
    agent.set(l)
  })

  def bind(port: Int): Future[Http.ServerBinding] = {
    val route = get {
      path("api") {
        complete(agent.get)
      } ~ path("style.css") {
        getFromResource("style.css")
      } ~ pathEndOrSingleSlash {
        getFromResource("index.html")
      }
    }
    Http().bindAndHandle(route, "0.0.0.0", port)
  }
}

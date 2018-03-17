package io.tvc.tube
import java.time.Clock

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import cats.effect.IO
import classy.Read
import classy.config._
import classy.decoders._
import classy.generic._
import com.typesafe.config.{ConfigFactory, Config => TypesafeConfig}
import fs2.Scheduler
import fs2.async.mutable.Queue

import scala.collection.JavaConverters._

object Main extends App {

  implicit val as = ActorSystem()
  implicit val mat = ActorMaterializer()
  implicit val ec = mat.executionContext
  implicit val clock = Clock.systemUTC
  implicit val delayIO = Delay.fs2NativeDelay[IO](Scheduler[IO](4))

  implicit val readLineId: Read[TypesafeConfig, LineId] = Read.instance { path =>
    ConfigDecoder.instance { config => Right(LineId(config.getString(path))) } // todo guard getString
  }

  implicit val readBranchId: Read[TypesafeConfig, BranchId] = Read.instance { path =>
    ConfigDecoder.instance { config => Right(BranchId(config.getString(path))) }
  }

  implicit val readDirection: Read[TypesafeConfig, List[Direction]] = Read.instance { path =>
    ConfigDecoder.instance { config => // todo there is no error handling here at all
      Right(config.getStringList(path).asScala.flatMap(Direction.fromStringId).toList)
    }
  }

  val tsConfig: TypesafeConfig = ConfigFactory.load
  val safeConfig = deriveDecoder[TypesafeConfig, Config].decode(tsConfig)
  val config = safeConfig.right.getOrElse(throw new Exception(s"$safeConfig"))

  val client = TflClient.akkaClient(config.api, Http())

  val circle = config.lines.find(_.name == "Victoria").get
  val branch = circle.branches.head

  val io: IO[Queue[IO, DirectedInterval]] = for {
    suspended <- Flow.queue(client, circle.id, branch)
    _ <- suspended.run.runAsync(IO.fromEither)
  } yield suspended.q
}

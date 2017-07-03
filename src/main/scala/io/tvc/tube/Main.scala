package io.tvc.tube
import java.nio.charset.Charset
import java.time.Clock

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import cats.data.EitherT
import classy.generic._
import classy.Read
import com.typesafe.config.{ConfigFactory, Config => TypesafeConfig}
import cats.instances.future._
import classy.config._
import classy.decoders._

import scala.concurrent.duration._
import scala.concurrent.Future
import scala.collection.JavaConverters._

object Main extends App {

  implicit val as = ActorSystem()
  implicit val mat = ActorMaterializer()
  implicit val ec = mat.executionContext
  implicit val clock = Clock.systemUTC

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
  val client = new TflClient(config.api)

  def runApp: Future[Unit] = {

    val departures = for {
      line <- config.lines
      branch <- line.branches
    } yield EitherT(client.departures(line.id, branch)).fold({ err =>
      println(err)
      None
    }, Some(_))

    for {
     departures <- Future.sequence(departures).map(_.flatten.toList)
     result <- Metrics.put(departures)
    } yield println(departures)
  }

  as.scheduler.schedule(0.minutes, 5.minutes)(runApp)
  val binding = new Routes(config.lines).bind(config.port).recover { case e: Throwable =>
    println(e)
    sys.exit()
  }
}

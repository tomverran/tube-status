package io.tvc.tube

import java.time.{Clock, ZonedDateTime}
import java.util.Date
import java.util.concurrent.CompletableFuture

import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import software.amazon.awssdk.services.cloudwatch.model._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.collection.JavaConverters._
import cats.syntax.cartesian._
import cats.instances.future._

object Metrics {

  private val lineBuilder = Dimension.builder.name("Line")
  private val branchBuilder = Dimension.builder.name("Branch")
  private val directionBuilder = Dimension.builder.name("Direction")
  private val interval = "interval"

  private val client: CloudWatchAsyncClient =
    CloudWatchAsyncClient.builder.region(Region.EU_WEST_1).build

  private def toScala[T](c: CompletableFuture[T]): Future[T] = {
    val promise = Promise[T]()
    c.whenComplete { (r, e) => Option(e).fold(promise.success(r))(promise.failure) }
    promise.future
  }

  private def intervalToMetrics(s: ServiceInterval): List[MetricDatum] = {
    val commonDimensions = List(
      lineBuilder.value(s.lineId.value).build,
      branchBuilder.value(s.branchId.value).build
    )
    s.intervals.map { case DirectedInterval(direction, int) =>
      MetricDatum.builder
        .metricName(interval)
        .dimensions((directionBuilder.value(direction.id).build :: commonDimensions).asJava)
        .value(int.toSeconds.toDouble)
        .build
    }
  }

  private def usualInterval(lineId: LineId, branch: BranchId, direction: Direction)(implicit clock: Clock, ec: ExecutionContext): Future[Option[FiniteDuration]] = {
    val now = ZonedDateTime.now(clock)
    val req = GetMetricStatisticsRequest.builder
      .dimensions(
        lineBuilder.value(lineId.value).build,
        branchBuilder.value(branch.value).build,
        directionBuilder.value(direction.id).build
      )
      .namespace("tube")
      .metricName("interval")
      .extendedStatistics("p70")
      .period(1.day.toSeconds.toInt)
      .startTime(Date.from(now.minusDays(1).toInstant))
      .endTime(Date.from(now.toInstant))
      .build
    toScala(client.getMetricStatistics(req)).map { resp =>
      for {
        dataPoint <- resp.datapoints.asScala.headOption
        p70 <- dataPoint.extendedStatistics.asScala.get("p70")
      } yield p70.toInt.seconds
    }
  }

  private def recentInterval(lineId: LineId, branchId: BranchId, direction: Direction)(implicit clock: Clock, ec: ExecutionContext): Future[Option[FiniteDuration]] = {
    val now = ZonedDateTime.now(clock)
     val req = GetMetricStatisticsRequest.builder
      .dimensions(
        lineBuilder.value(lineId.value).build,
        branchBuilder.value(branchId.value).build,
        directionBuilder.value(direction.id).build
      )
      .namespace("tube")
      .metricName("interval")
      .statistics("Average")
      .period(30.minutes.toSeconds.toInt)
      .startTime(Date.from(now.minusMinutes(30).toInstant))
      .endTime(Date.from(now.toInstant))
      .build

    toScala(client.getMetricStatistics(req)).map { resp =>
      for {
        dataPoint <- resp.datapoints.asScala.headOption
        avg <- Option(dataPoint.average)
      } yield avg.toInt.seconds
    }
  }

  def serviceLevels(lines: List[Line])(implicit c: Clock, ec: ExecutionContext): Future[List[LineStatus]] = {
    val info = for {
      line <- lines
      branch <- line.branches
      direction <- line.directions
    } yield (line, branch, direction)

    Future.sequence(
      info.map { case (line, branch, direction) =>
        (
          Metrics.usualInterval(line.id, branch.id, direction) |@|
            Metrics.recentInterval(line.id, branch.id, direction)
          ).map {
          case (Some(usual), Some(recent)) if usual >= recent =>
            line -> BranchStatus(branch.name, direction, Good(usual, recent))
          case (Some(usual), Some(recent)) =>
            line -> BranchStatus(branch.name, direction, Bad(usual, recent))
          case (_, _) =>
           line -> BranchStatus(branch.name, direction, NoTrains)
        }
      }
    ).map(_.groupBy(_._1).map { case (line, i) =>
      LineStatus(line, i.map(_._2))
    }.toList)
  }

  def put(deps: List[ServiceInterval]): Future[PutMetricDataResponse] = {
    val request = PutMetricDataRequest.builder.metricData(deps.flatMap(intervalToMetrics).asJava).namespace("tube").build
    toScala(client.putMetricData(request))
  }
}

package io.tvc.tube

import java.time.{Clock, ZonedDateTime}
import java.util.Date
import java.util.concurrent.{CompletableFuture, Executors}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.collection.JavaConverters._
import cats.syntax.cartesian._
import cats.instances.future._
import com.amazonaws.regions.Regions
import com.amazonaws.services.cloudwatch.{AmazonCloudWatch, AmazonCloudWatchClient}
import com.amazonaws.services.cloudwatch.model._

object Metrics {

  private val interval = "interval"

  private val client: AmazonCloudWatch =
    AmazonCloudWatchClient.builder.withRegion(Regions.EU_WEST_1).build

  private implicit val ec = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(4))

  private def intervalToMetrics(s: ServiceInterval): List[MetricDatum] = {
    val commonDimensions = List(
      new Dimension().withName("Line").withValue(s.lineId.value),
      new Dimension().withName("Branch").withValue(s.branchId.value)
    )
    s.intervals.map { case DirectedInterval(direction, int) =>
      new MetricDatum()
        .withMetricName(interval)
        .withDimensions((new Dimension().withName("Direction").withValue(direction.id) :: commonDimensions).asJava)
        .withValue(int.toSeconds.toDouble)
    }
  }

  private def usualInterval(lineId: LineId, branch: BranchId, direction: Direction)(implicit clock: Clock): Future[Option[FiniteDuration]] = {
    val now = ZonedDateTime.now(clock)
    val req = new GetMetricStatisticsRequest()
      .withDimensions(
        new Dimension().withName("Line").withValue(lineId.value),
        new Dimension().withName("Branch").withValue(branch.value),
        new Dimension().withName("Direction").withValue(direction.id)
      )
      .withNamespace("tube")
      .withMetricName("interval")
      .withExtendedStatistics("p70")
      .withPeriod(1.day.toSeconds.toInt)
      .withStartTime(Date.from(now.minusDays(1).toInstant))
      .withEndTime(Date.from(now.toInstant))
    Future(client.getMetricStatistics(req)).map { resp =>
      for {
        dataPoint <- resp.getDatapoints.asScala.headOption
        p70 <- dataPoint.getExtendedStatistics.asScala.get("p70")
      } yield p70.toInt.seconds
    }
  }

  private def recentInterval(lineId: LineId, branch: BranchId, direction: Direction)(implicit clock: Clock): Future[Option[FiniteDuration]] = {
    val now = ZonedDateTime.now(clock)
    val req = new GetMetricStatisticsRequest()
      .withDimensions(
        new Dimension().withName("Line").withValue(lineId.value),
        new Dimension().withName("Branch").withValue(branch.value),
        new Dimension().withName("Direction").withValue(direction.id)
      )
      .withNamespace("tube")
      .withMetricName("interval")
      .withStatistics("Average")
      .withPeriod(30.minutes.toSeconds.toInt)
      .withStartTime(Date.from(now.minusMinutes(30).toInstant))
      .withEndTime(Date.from(now.toInstant))

    Future(client.getMetricStatistics(req)).map { resp =>
      for {
        dataPoint <- resp.getDatapoints.asScala.headOption
        avg <- Option(dataPoint.getAverage)
      } yield avg.toInt.seconds
    }
  }

  def serviceLevels(lines: List[Line])(implicit c: Clock): Future[List[LineStatus]] = {
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
    }.toList.sortBy(_.line.name))
  }

  def put(deps: List[ServiceInterval]): Future[List[PutMetricDataResult]] = {
    Future.sequence(
      deps.flatMap(intervalToMetrics).grouped(20).map { metricData =>
        val request = new PutMetricDataRequest().withMetricData(metricData.asJava).withNamespace("tube")
        Future(client.putMetricData(request))
      }.toList
    )
  }
}

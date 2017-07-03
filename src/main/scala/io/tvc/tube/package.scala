package io.tvc

import java.time.ZonedDateTime

import scala.concurrent.duration.FiniteDuration

package object tube {

  sealed trait ServiceLevel
  case object NoTrains extends ServiceLevel
  case class Good(usually: FiniteDuration, currently: FiniteDuration) extends ServiceLevel
  case class Bad(usually: FiniteDuration, currently: FiniteDuration) extends ServiceLevel

  sealed abstract class Direction(val id: String, val name: String)
  case object Northbound extends Direction("north", "Northbound")
  case object Eastbound extends Direction("east", "Eastbound") // and down
  case object Southbound extends Direction("south", "Southbound")
  case object Westbound extends Direction("west", "Westbound")

  object Direction {
    def fromStringId(id: String): Option[Direction] =
      List(Northbound, Eastbound, Southbound, Westbound).find(_.id == id)
    def fromStringName(name: String): Option[Direction] =
      List(Northbound, Eastbound, Southbound, Westbound).find(_.name == name)
  }

  case class LineId(value: String)
  case class LineDisplay(bg: String, fg: String)

  case class Line(
    id: LineId,
    name: String,
    directions: List[Direction],
    display: LineDisplay,
    branches: List[Branch]
  )

  case class BranchId(value: String)
  case class Branch(id: BranchId, name: Option[String], stopId: String)

  case class DirectedInterval(direction: Direction, interval: FiniteDuration)

  case class ServiceInterval(
    time: ZonedDateTime,
    lineId: LineId,
    branchId: BranchId,
    intervals: List[DirectedInterval]
  )

  case class Config(port: Int, lines: List[Line])
  case class BranchStatus(name: Option[String], direction: Direction, level: ServiceLevel)
  case class LineStatus(line: Line, branches: List[BranchStatus])
}

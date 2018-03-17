package io.tvc.tube

import cats.effect.Effect
import fs2.{Pipe, Scheduler, Stream}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import scala.language.higherKinds

trait Delay[F[_]] {
  def delay[A](dur: FiniteDuration): Pipe[F, A, A]
}

object Delay {

  def apply[F[_] : Delay]: Delay[F] =
    implicitly

  def fs2NativeDelay[F[_] : Effect](scheduler: Stream[F, Scheduler])(implicit e: ExecutionContext): Delay[F] =
    new Delay[F] {
      override def delay[A](dur: FiniteDuration): Pipe[F, A, A] =
        stream => scheduler.flatMap(s => s.sleep(dur).flatMap(_ => stream))

    }
}

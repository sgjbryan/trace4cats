package trace4cats

import cats.Applicative
import cats.effect.kernel.syntax.spawn._
import cats.effect.kernel.{Clock, Ref, Resource, Temporal}
import cats.effect.std.Queue
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.monad._
import fs2.{Chunk, Stream}
import org.typelevel.log4cats.Logger

import scala.concurrent.duration._

object QueuedSpanCompleter2 {
  def apply[F[_]: Temporal: Logger](
    process: TraceProcess,
    exporter: SpanExporter[F, Chunk],
    config: CompleterConfig
  ): Resource[F, SpanCompleter[F]] = {
    val realBufferSize = if (config.bufferSize < config.batchSize * 5) config.batchSize * 5 else config.bufferSize

    for {
      hasLoggedWarn <- Resource.eval(Ref.of(false))
      queue <- Resource.eval(Queue.bounded[F, CompletedSpan](realBufferSize))
      _ <- Resource.make {
        Stream
          .fromQueueUnterminated(queue)
          .groupWithin(config.batchSize, config.batchTimeout)
          .map(spans => Batch(spans))
          .evalMap { batch =>
            Stream
              .retry(
                exporter.exportBatch(batch),
                delay = config.retryConfig.delay,
                nextDelay = config.retryConfig.nextDelay.calc,
                maxAttempts = config.retryConfig.maxAttempts
              )
              .compile
              .drain
              .onError { case th =>
                Logger[F].warn(th)("Failed to export spans")
              }
          }
          .compile
          .drain
          .start
      }(fiber => Clock[F].sleep(50.millis).whileM_(queue.size.map(_ != 0)) >> fiber.join.void)
    } yield new SpanCompleter[F] {
      override def complete(span: CompletedSpan.Builder): F[Unit] = {

        val warnLog = hasLoggedWarn.get
          .map(!_)
          .ifM(
            Logger[F].warn(s"Failed to enqueue new span, buffer is full of $realBufferSize") >> hasLoggedWarn.set(true),
            Applicative[F].unit,
          )

        queue
          .tryOffer(span.build(process))
          .ifM(hasLoggedWarn.set(false), warnLog)
      }
    }
  }
}

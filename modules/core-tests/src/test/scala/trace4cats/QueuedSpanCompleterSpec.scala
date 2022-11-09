package trace4cats

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.std.Random
import cats.effect.testkit.TestInstances
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import fs2.Chunk
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import trace4cats.model.CompletedSpan
import trace4cats.test.ArbitraryInstances._

import scala.concurrent.duration._

class QueuedSpanCompleterSpec extends AnyFlatSpec with Matchers with TestInstances with ScalaCheckDrivenPropertyChecks {
  behavior.of("QueuedSpanCompleter")

  def stubExporter(ref: Ref[IO, Int]): SpanExporter[IO, Chunk] = new SpanExporter[IO, Chunk] {
    def exportBatch(batch: Batch[Chunk]): IO[Unit] = IO.sleep(10.millis) >> ref.update(_ + batch.spans.size)
  }

  implicit def logger: Logger[IO] = Slf4jLogger.getLogger[IO]

  // Note: this test was hanging with high rps, since inFlight never reached _exactly_ 0 in that case -> updated draining check to allow values below 0
  // 10 is fine, with max blocking latency of ~3ms
  // 100 seems fine
  // 500 consistently fails with max blocking latencies > 1s
  val rps = 500

  it should "not block on complete" in forAll { (builder: CompletedSpan.Builder) =>
    val test = for {
      ref <- Ref.of[IO, Int](0)
      random: Random[IO] <- Random.scalaUtilRandom[IO]
      exporter = stubExporter(ref)
      res <- QueuedSpanCompleter[IO](
        TraceProcess("completer-test"),
        exporter,
        CompleterConfig(bufferSize = 5, batchSize = 1)
      ).use { completer =>
        val randomRequestTime = for {
          rand <- random.betweenDouble(0, 1)
          _ <- IO.sleep((1.second * rand).asInstanceOf[FiniteDuration])
          (time, _) <- completer.complete(builder).timed
        } yield time
        List.fill(rps)(randomRequestTime).parSequence.map(_.max)
      }
      total <- ref.get
      _ <- IO.println(total)
      _ <- IO.println(show"${res.toMillis}ms")
    } yield res

    val result = test.unsafeRunSync()
    result shouldBe (<(10.millis))
  }
}

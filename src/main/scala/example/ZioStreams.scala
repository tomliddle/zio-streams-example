package example

import zio.clock.Clock
import zio.console.{Console, putStrLn}
import zio.json.{DecoderOps, DeriveJsonDecoder, DeriveJsonEncoder, EncoderOps, JsonDecoder, JsonEncoder}
import zio.stream.{ZSink, ZStream}
import zio.{App, ExitCode, Has, IO, RIO, RManaged, UIO, URIO, ZIO, ZLayer, console}
import example.Codecs._
import org.apache.kafka.clients.producer.{ProducerRecord, RecordMetadata}
import scalaz.Leibniz.subst
import zio.blocking.Blocking
import zio.kafka.consumer._
import zio.kafka.producer.{Producer, ProducerSettings}
import zio.kafka.serde._

import java.util.UUID

trait InputEvent
case class Player(name: String) extends InputEvent

object Codecs {
  implicit val decoder: JsonDecoder[Player] = DeriveJsonDecoder.gen[Player]
  implicit val encoder: JsonEncoder[Player] = DeriveJsonEncoder.gen[Player]
}

object ZioStreams extends App {

  val playerSerde: Serde[Any, Player] = Serde.string.inmapM { matchAsString =>
    ZIO.fromEither(matchAsString.fromJson[Player].left.map(new RuntimeException(_)))
  }(matchAsObj => ZIO.effect(matchAsObj.toJson))

  val producerSettings: ProducerSettings = ProducerSettings(List("localhost:9092"))

  val producer: ZLayer[Blocking, Throwable, Has[Producer]] =
    ZLayer.fromManaged(Producer.make(producerSettings))

  val messagesToSend: ProducerRecord[String, Player] =
    new ProducerRecord(
      "updates",
      "test",
      Player("name"),
    )

  val producerEffect: RIO[Any with Has[Producer], RecordMetadata] =
    Producer.produce(messagesToSend, Serde.string, playerSerde)

  val consumerSettings: ConsumerSettings =
    ConsumerSettings(List("localhost:9092"))
      .withGroupId("updates")

  val managedConsumer: RManaged[Clock with Blocking, Consumer] =
    Consumer.make(consumerSettings)

  val consumer: ZLayer[Clock with Blocking, Throwable, Has[Consumer]] =
    ZLayer.fromManaged(managedConsumer)

  val stream: ZStream[Console with Any with Has[Consumer], Throwable, CommittableRecord[String, Player]] = {
    for {
      stream <- Consumer
        .subscribeAnd(Subscription.topics("topic"))
        .plainStream(Serde.string, playerSerde)
        .tap(s => console.putStrLn(s"Player read ${s.value}"))
    } yield stream
  }

  val program: ZIO[Console with Any with Has[Consumer] with Clock, Throwable, Unit] = for {
    _ <- putStrLn("Running sink on stream...")
    _ <- stream
      .map(cr => (cr.value.name, cr.offset))
      .tap { case (score, _) => console.putStrLn(s"| $score |") }
      .map { case (_, offset) => offset }
      .aggregateAsync(Consumer.offsetBatches)
      .run(ZSink.foreach(_.commit))
    _ <- stream.run(ZSink.foldLeftM(1) { (count: Int, streamItem: CommittableRecord[String, Player]) =>
      putStrLn(s"Consuming stream, iteration: $count, with item: ${streamItem.value}") *> IO.succeed(count + 1)
    })
    _ <- putStrLn("Finished reading from stream.")
  } yield ()

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
    for {
      p <- program.provideCustomLayer(consumer).catchAll(_ => UIO.succeed(false)).as(ExitCode.success)
      a <- producerEffect.provideSomeLayer(producer).provideLayer(Blocking.live).catchAll(_ => UIO.succeed(false))
    } yield p
}

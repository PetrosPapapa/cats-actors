package com.suprnation.actor

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.unsafe.implicits.global
import com.suprnation.actor.ActorRef.ActorRef
import com.suprnation.actor.debug.TrackingActor
import com.suprnation.actor.debug.TrackingActor.ActorRefs
import com.suprnation.actor.{Actor, ActorSystem}
import com.suprnation.actor.test.TestKit
import com.suprnation.actor.dungeon.TimerSchedulerImpl
import com.suprnation.typelevel.actors.syntax.ActorSystemDebugOps
import org.scalatest.Assertion
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

class TimerExampleSpec extends AsyncFlatSpec with Matchers with TestKit {
  import TimerExample._

  type Fixture = (ActorSystem[IO], ActorRef[IO, Request])

  protected def testExample(test: Fixture => IO[Assertion]): Future[Assertion] =
    ActorSystem[IO]()
      .use { actorSystem =>
        for {
          timerGenRef <- Timers.initGenRef[IO]
          timersRef <- Timers.initTimersRef[IO, String]
          cache <- ActorRefs
            .empty[IO]
            .map(
              _.copy(
                timerGenRef = timerGenRef,
                timersRef = timersRef
              )
            )
          stableName = "tracker"
          cacheMap <- Ref.of[IO, Map[String, ActorRefs[IO]]](Map(stableName -> cache))
          actorRef <- actorSystem.actorOf(
            TrackingActor.create[IO, Any, Any](
              cache = cacheMap,
              stableName = stableName,
              proxy = TimerActor(100.millis, 550.millis, timerGenRef, timersRef).widen[Any]
            )
          )

          result <- test(actorSystem, actorRef)
        } yield result
      }
      .unsafeToFuture()

  "receiveWhile" should "receive Hellos until we hit Enough" in {
    testExample { case (_, actorRef) =>
      for {
        receivedMessage <- receiveWhile(actorRef, 1 second) { case req: Request =>
          req
        }
      } yield {
        receivedMessage.distinct should have size 2
        receivedMessage shouldBe Seq(Hello, Hello, Hello, Hello, Hello, Enough)
      }
    }
  }

}
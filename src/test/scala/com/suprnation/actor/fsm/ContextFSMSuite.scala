/*
 * Copyright 2024 SuprNation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.suprnation.actor.fsm

import cats.effect.unsafe.implicits.global
import cats.effect.{Deferred, IO, Ref}
import cats.implicits.catsSyntaxApplicativeId
import com.suprnation.actor.fsm.ContextFSMSuite._
import com.suprnation.actor.Actor.{Actor, Receive}
import com.suprnation.actor.fsm.FSM.Event
import com.suprnation.actor.fsm.{FSM, FSMConfig}
import com.suprnation.actor.{ActorSystem, ReplyingActor}
import com.suprnation.typelevel.actors.syntax.ActorSystemDebugOps
import com.suprnation.typelevel.fsm.syntax.FSMStateSyntaxOps
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.DurationInt

object ContextFSMSuite {

  private[ContextFSMSuite] sealed trait FsmParentState
  private[ContextFSMSuite] case object FsmIdle extends FsmParentState
  private[ContextFSMSuite] case object FsmRunning extends FsmParentState

  private[ContextFSMSuite] sealed trait FsmRequest
  private[ContextFSMSuite] case object FsmRun extends FsmRequest
  private[ContextFSMSuite] case object FsmStop extends FsmRequest

  private[ContextFSMSuite] sealed trait FsmChildRequest
  private[ContextFSMSuite] case object FsmChildEcho extends FsmChildRequest

  private[ContextFSMSuite] case class FsmChild() extends Actor[IO, FsmChildRequest] {

    override def receive: Receive[IO, FsmChildRequest] = { case FsmChildEcho =>
      FsmChildEcho.pure[IO]
    }
  }

  def actor(
      startWith: FsmParentState,
      stopped: Deferred[IO, Boolean]
  ): IO[ReplyingActor[IO, FsmRequest, List[Any]]] =
    FSM[IO, FsmParentState, Int, FsmRequest, List[Any]]
      .when(FsmIdle)(sM => { case Event(FsmRun, _) =>
        for {
          fsmChildActor <- sM.actorContext.actorOf(FsmChild())
          result <- fsmChildActor ? FsmChildEcho
          state <- sM.goto(FsmRunning).returning(List(result))
        } yield state
      })
      .when(FsmRunning)(sM => {
        case Event(FsmRun, _) =>
          (sM.actorContext.self ! FsmStop) *> sM.stay()
        case Event(FsmStop, _) =>
          stopped.complete(true) *> sM.stay()
      })
      .startWith(startWith, 0)
      .initialize

}

class ContextFSMSuite extends AsyncFlatSpec with Matchers {

  it should "create child actor and send a message to self" in {
    ActorSystem[IO]("FSM Actor")
      .use { actorSystem =>
        for {
          stoppedRef <- Deferred[IO, Boolean]
          fsmActor <- actorSystem.replyingActorOf(
            ContextFSMSuite.actor(
              startWith = FsmIdle,
              stoppedRef
            )
          )

          r0 <- fsmActor ? FsmRun
          r1 <- fsmActor ? FsmRun

          _ <- IO.race(
            IO.delay(fail("Timed out waiting for stop message")).delayBy(4.seconds),
            stoppedRef.get.map(_ should be(true))
          )
          _ <- actorSystem.waitForIdle()
        } yield List(r0, r1).flatten
      }
      .unsafeToFuture()
      .map { messages =>
        messages.toList should be(List(FsmChildEcho))
      }
  }

}

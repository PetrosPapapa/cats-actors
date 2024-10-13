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

package com.suprnation.actor.ask

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import cats.effect.implicits._
import cats.implicits._
import com.suprnation.actor.Actor.ReplyingReceive
import com.suprnation.actor.{ActorSystem, ReplyingActor}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.Assertions
import org.scalatest.exceptions.TestFailedException

object AskSpec {
  sealed trait Input
  case class Hi(response: Int) extends Input
  case class Error(error: String) extends Exception(error) with Input

  lazy val askReceiver: ReplyingActor[IO, Input, Int] = new ReplyingActor[IO, Input, Int] {
    override def receive: ReplyingReceive[IO, Any, Int] = {
      case Hi(response)       => response.pure[IO]
      case err @ Error(error) => IO.raiseError(err)
    }
  }
}

class AskSpec extends AsyncFlatSpec with Matchers with Assertions {

  import AskSpec._

  it should "return the correct response" in {
    ActorSystem[IO]("AskSpec")
      .use { system =>
        for {
          actor <- system.replyingActorOf(askReceiver)

          response <- actor ? Hi(4567)
        } yield response shouldBe 4567
      }
      .unsafeToFuture()

  }

  it should "catch errors" in {
    recoverToSucceededIf[Error] {
      ActorSystem[IO]("AskSpec")
        .use { system =>
          for {
            actor <- system.replyingActorOf(askReceiver)

            response <- actor ? Error("oops")
          } yield ()
        }
        .unsafeToFuture()
    }

  }

}

package com.soljvm.student.app

import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import com.soljvm.student.actors.Student
import com.soljvm.student.actors.StudentAccountByPersistence.Command
import akka.actor.typed.scaladsl.AskPattern._
import akka.http.scaladsl.Http
import akka.util.Timeout
import com.soljvm.student.http.StudentRouter

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Try, Success, Failure}

object StudentApp {

  def startHttpServer(student: ActorRef[Command])(implicit system: ActorSystem[_]): Unit = {
    implicit val ec: ExecutionContext = system.executionContext
    val router = new StudentRouter(student)
    val routes = router.routes

    val httpBindingFuture = Http().newServerAt("localhost", 8080).bind(routes)
    httpBindingFuture.onComplete {
      case Success(binding) =>
        val address = binding.localAddress
        system.log.info(s"Server online at http://${address.getHostString}:${address.getPort}")
      case Failure(ex) =>
        system.log.error(s"Failed to bind HTTP server, because: $ex")
        system.terminate()
    }
  }

  def main(args: Array[String]): Unit = {
    trait RootCommand
    case class RetrieveStudentActor(replyTo: ActorRef[ActorRef[Command]]) extends RootCommand

    val rootBehavior: Behavior[RootCommand] = Behaviors.setup { context =>
      val studentActor = context.spawn(Student(), "student")
      Behaviors.receiveMessage {
        case RetrieveStudentActor(replyTo) =>
          replyTo ! studentActor
          Behaviors.same
      }
    }

    implicit val system: ActorSystem[RootCommand] = ActorSystem(rootBehavior, "StudentSystem")
    implicit val timeout: Timeout = Timeout(5.seconds)
    implicit val ec: ExecutionContext = system.executionContext

    val studentActorFuture: Future[ActorRef[Command]] = system.ask(replyTo => RetrieveStudentActor(replyTo))
    studentActorFuture.foreach(startHttpServer)
  }
}

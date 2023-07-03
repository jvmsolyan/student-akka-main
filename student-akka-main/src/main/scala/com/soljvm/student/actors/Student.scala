package com.soljvm.student.actors

import akka.NotUsed
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, Scheduler}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import akka.util.Timeout

import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.util.Failure


object Student {

  // commands = messages
  import StudentAccountByPersistence.Command._
  import StudentAccountByPersistence.Response._
  import StudentAccountByPersistence.Command
  import StudentAccountByPersistence.Response

  // events
  sealed trait Event
  case class StudentAccountCreated(id: String) extends Event

  // state
  case class State(accounts: Map[String, ActorRef[Command]])

  // command handler
  def commandHandler(context: ActorContext[Command]): (State, Command) => Effect[Event, State] = (state, command) =>
    command match {
      case createCommand @ CreateStudentAccount(_, _, _, _) =>
        val id = UUID.randomUUID().toString
        val newStudentAccount = context.spawn(StudentAccountByPersistence(id), id)
        Effect
          .persist(StudentAccountCreated(id))
          .thenReply(newStudentAccount)(_ => createCommand)

      case updateCmd @ UpdateStudent(id, _, _, replyTo) =>
        state.accounts.get(id) match {
          case Some(account) =>
            Effect.reply(account)(updateCmd)
          case None =>
            Effect.reply(replyTo)(StudentAccountCreditsUpdatedResponse(Failure(new RuntimeException("Student account cannot be found")))) // failed account search
        }
      case getCmd @ GetStudentAccount(id, replyTo) =>
        state.accounts.get(id) match {
          case Some(account) =>
            Effect.reply(account)(getCmd)
          case None =>
            Effect.reply(replyTo)(GetStudentAccountResponse(None)) // failed search
        }
    }

  // event handler
  def eventHandler(context: ActorContext[Command]): (State, Event) => State = (state, event) =>
    event match {
      case StudentAccountCreated(id) =>
        val account = context.child(id) // exists after command handler,
          .getOrElse(context.spawn(StudentAccountByPersistence(id), id)) // does NOT exist in the recovery mode, so needs to be created
          .asInstanceOf[ActorRef[Command]]
        state.copy(state.accounts + (id -> account))
    }

  // behavior
  def apply(): Behavior[Command] = Behaviors.setup { context =>
    EventSourcedBehavior[Command, Event, State](
      persistenceId = PersistenceId.ofUniqueId("student"),
      emptyState = State(Map()),
      commandHandler = commandHandler(context),
      eventHandler = eventHandler(context)
    )
  }
}

object StudentPlayground {
  import StudentAccountByPersistence.Command._
  import StudentAccountByPersistence.Response._
  import StudentAccountByPersistence.Response

  def main(args: Array[String]): Unit = {
    val rootBehavior: Behavior[NotUsed] = Behaviors.setup { context =>
      val student = context.spawn(Student(), "student")
      val logger = context.log

      val responseHandler = context.spawn(Behaviors.receiveMessage[Response]{
        case StudentAccountCreatedResponse(id) =>
          logger.info(s"successfully created Student account $id")
          Behaviors.same
        case GetStudentAccountResponse(isStudentAccount) =>
          logger.info(s"Student Account details: $isStudentAccount")
          Behaviors.same
      }, "replyHandler")


      import akka.actor.typed.scaladsl.AskPattern._
      import scala.concurrent.duration._
      implicit val timeout: Timeout = Timeout(2.seconds)
      implicit val scheduler: Scheduler = context.system.scheduler
      implicit val ec: ExecutionContext = context.executionContext

          student! CreateStudentAccount("Ivanna", 15, "ivykate@hotmail.com", responseHandler)
        // student ! GetStudentAccount("5271f25b-8a5d-4550-8ebe-1e49256cf6c4", responseHandler)

      Behaviors.empty
    }

    val system = ActorSystem(rootBehavior, "StudentDemo")
  }
}

package com.soljvm.student.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}

import scala.util.{Failure, Success, Try}

// a single student account
object 2StudentAccountByPersistence {

  // commands = messages
  sealed trait Command
  object Command {
    case class CreateStudentAccount(name: String, credits: Int, email: String, replyTo: ActorRef[Response]) extends Command
    case class UpdateStudent(id: String, credits: Int, email: String, replyTo: ActorRef[Response]) extends Command
    case class GetStudentAccount(id: String, replyTo: ActorRef[Response]) extends Command
  }

  // events = to persist to Cassandra
  trait Event
  case class StudentAccountCreated(studentAccount: StudentAccount) extends Event
  case class StudentModified(credits: Int,email: String) extends Event

  // state
  case class StudentAccount(id: String, name: String, credits: Int, email: String)

  // responses
  sealed trait Response
  object Response {
    case class StudentAccountCreatedResponse(id: String) extends Response
    case class StudentAccountCreditsUpdatedResponse(isStudentAccount: Try[StudentAccount]) extends Response
    case class GetStudentAccountResponse(isStudentAccount: Option[StudentAccount]) extends Response

  }

  import Command._
  import Response._

  // command handler = message handler => persist an event
  // event handler => update state
  // state

  val commandHandler: (StudentAccount, Command) => Effect[Event, StudentAccount] = (state, command) =>
    command match {
      case CreateStudentAccount(name, credits, email, student) =>
        val id = state.id
        /*
          - student creates me
          - student sends me CreateStudentAccount
          - I persist StudentAccountCreated
          - I update my state
          - reply back to bank with the StudentAccountCreatedResponse

         */
        Effect
          .persist(StudentAccountCreated(StudentAccount(id, name, credits, email))) // persisted into Cassandra
          .thenReply(student)(_ => StudentAccountCreatedResponse(id))
      case UpdateStudent(_, credits, email, student) =>
        val newCredits = state.credits + credits

        if (newCredits < 0) // illegal
          Effect.reply(student)(StudentAccountCreditsUpdatedResponse(Failure(new RuntimeException("Credit Cannot be lees than zero "))))
        else
          Effect
            .persist(StudentModified(credits,email))
              .thenReply(student)(newState => StudentAccountCreditsUpdatedResponse(Success(newState)))

      case GetStudentAccount(_, student) =>
        Effect.reply(student)(GetStudentAccountResponse(Some(state)))
    }

  val eventHandler: (StudentAccount, Event) => StudentAccount = (state, event) =>
    event match {
      case StudentAccountCreated(studentAccount) =>
       studentAccount
      case StudentModified(credits, _) =>
        state.copy(credits = state.credits + credits)
    }

  def apply(id: String): Behavior[Command] =
    EventSourcedBehavior[Command, Event, StudentAccount](
      persistenceId = PersistenceId.ofUniqueId(id),
      emptyState = StudentAccount(id,"", 0,""), // unused
      commandHandler = commandHandler,
      eventHandler = eventHandler
    )
}

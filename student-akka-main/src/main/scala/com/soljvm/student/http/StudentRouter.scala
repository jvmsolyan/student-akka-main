package com.soljvm.student.http

import akka.http.scaladsl.server.Directives._
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.Location
import com.soljvm.student.actors.StudentAccountByPersistence.Command
import com.soljvm.student.actors.StudentAccountByPersistence.Command._
import com.soljvm.student.actors.StudentAccountByPersistence.Response
import com.soljvm.student.actors.StudentAccountByPersistence.Response._
import io.circe.generic.auto._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import akka.actor.typed.scaladsl.AskPattern._
import akka.util.Timeout

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Success, Failure}
import akka.http.scaladsl.server.Route
import cats.data.Validated.{Invalid, Valid}
import cats.implicits._

case class StudentAccountCreationRequest(name: String, credits: Int, email: String) {
  def toCommand(replyTo: ActorRef[Response]): Command = CreateStudentAccount(name, credits,email, replyTo)
}



case class StudentAccountUpdateRequest(credits: Int, email: String) {
  def toCommand(id: String, replyTo: ActorRef[Response]): Command = UpdateStudent(id,credits,email, replyTo)
}



case class FailureResponse(reason: String)

class StudentRouter(student: ActorRef[Command])(implicit system: ActorSystem[_]) {
  implicit val timeout: Timeout = Timeout(5.seconds)

  def createStudentAccount(request: StudentAccountCreationRequest): Future[Response] =
    student.ask(replyTo => request.toCommand(replyTo))

  def getStudentAccount(id: String): Future[Response] =
    student.ask(replyTo => GetStudentAccount(id, replyTo))

  def updateStudentAccount(id: String, request: StudentAccountUpdateRequest): Future[Response] =
    student.ask(replyTo => request.toCommand(id, replyTo))


  /*
    POST /student/
      Payload: student account creation request as JSON
      Response:
        201 Created
        Location: /student/uuid

    GET /student/uuid
      Response:
        200 OK
        JSON repr of student account details

        404 Not found

    PUT /student/uuid
      Payload: (credits, email) as JSON
      Response:
        1)  200 OK
            Payload: new student details as JSON
        2)  404 Not found
   */
  val routes =
    pathPrefix("student") {
      pathEndOrSingleSlash {
        post {
          // parse the payload
          entity(as[StudentAccountCreationRequest]) { request =>
           
              onSuccess(createStudentAccount(request)) {
                // send back an HTTP response
                case StudentAccountCreatedResponse(id) =>
                  respondWithHeader(Location(s"/student/$id")) {
                    complete(StatusCodes.Created)
                  }
              }
      
          }
        }
      } ~
        path(Segment) { id =>
          get {
           
            onSuccess(getStudentAccount(id)) {
              //  - send back the HTTP response
              case GetStudentAccountResponse(Some(account)) =>
                complete(account) // 200 OK
              case GetStudentAccountResponse(None) =>
                complete(StatusCodes.NotFound, FailureResponse(s"Student account $id cannot be found."))
            }
          } ~
            put {
              entity(as[StudentAccountUpdateRequest]) { request =>
               
                  onSuccess(updateStudentAccount(id, request)) {
                    // send HTTP response
                    case StudentAccountCreditsUpdatedResponse(Success(account)) =>
                      complete(account)
                    case StudentAccountCreditsUpdatedResponse(Failure(ex)) =>
                      complete(StatusCodes.BadRequest, FailureResponse(s"${ex.getMessage}"))
                  }
             
              }
            }
        }
    }

}

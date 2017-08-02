package moe.taiho.course_selection.cluster

import akka.Done
import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model._
import scala.concurrent.ExecutionContext.Implicits.global

import scala.concurrent.duration._
import akka.pattern.ask
import scala.io.Source
import akka.http.scaladsl.Http.ServerBinding
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import moe.taiho.course_selection.actors.{Course, Student}

import scala.concurrent.Future
import scala.util.{Success, Try}
import io.circe._
import org.http4s._
import org.http4s.circe._
import org.http4s.server._
import org.http4s.dsl._
import fs2.Task


class Pinger (studentRegion: ActorRef, courseRegion: ActorRef){
	def pingUntilPong(reciver : String, id : Int): Unit = {
		print(s"\033[32m ${id}\033[0m")
		reciver match {
			case "student" =>
				implicit val askTimeout: Timeout = 10.seconds // set timeout
				(studentRegion ? Student.Envelope(id, Student.Ping())).mapTo[Student.Pong] onComplete {
					case Success(_) => // do nothing
					case _ => pingUntilPong("student", id)
				}
			case "course" =>
				implicit val askTimeout: Timeout = 10.seconds // set timeout
				((courseRegion ? Course.Envelope(id, Course.Ping())).mapTo[Course.Pong]) onComplete {
					case Success(_)  => // do nothing
					case _ => pingUntilPong("course", id) // redo it
				}
			case _ => // Do nothing
		}
	}
	def pingStudent(configFn : String): Unit = {
		for (studentID <- Source.fromResource(configFn).getLines) {
			pingUntilPong("student", studentID.toInt)
		}
	}

	def pingCourse(configFn : String): Unit = {
		for (courseID <- Source.fromResource(configFn).getLines) {
			pingUntilPong("course", courseID.toInt)
		}
	}
	def ping() {
		pingStudent ("student_id.conf")
		pingCourse ("course_id.conf")
	}
}

object SidQueryParaMatcher extends QueryParamDecoderMatcher[String]("sid")
object CidQueryParaMatcher extends QueryParamDecoderMatcher[String]("cid")
object SizeQueryParaMatcher extends QueryParamDecoderMatcher[String]("Size")

object Http4sServer {
	def run(studentRegion: ActorRef, courseRegion: ActorRef)(implicit system: ActorSystem): Future[ServerBinding] = {

		val service = HttpService {
			case GET -> Root / "hello" =>
				Ok(s"<h1>Say hello to akka-http</h1>")
			case GET -> Root / "ping" =>
				Ok({
				val t0 = System.nanoTime()
				val ping = new Pinger(studentRegion, courseRegion)
				ping.ping()
				val t1 = System.nanoTime()
				s"<h1>ping all actor in ${(t1-t0)/1000000} ms</h1>"
				})
			case POST -> Root / "demo" => Ok({
					implicit val askTimeout: Timeout = 10.seconds // set timeout
					(studentRegion ? Student.Envelope(10086, Student.DebugPrint("Debug Message"))).mapTo[String]) onComplete {
						case Success(result) => complete(result)
						case _ => complete(s"The server was not able " + "to produce a timely response to your request.\r\nPlease try again in a short while!")
					}
				})
			case POST -> Root / "take" :? SidQueryParaMatcher(studentId) +& CidQueryParaMatcher(courseId) =>
				Ok({
					val studentID = studentId.toInt + 1
						val courseID = courseId.toInt
						implicit val askTimeout: Timeout = 10.seconds // set timeout
						onComplete((studentRegion ? Student.Envelope(studentID, Student.Quit(courseID))).mapTo[Student.Info]) {
							case Success(res) =>
								res match {
									case Student.Success(_, course, _) => complete(s"Successes drop course'$course'")
									case Student.Failure(_, course, _, reason) => complete(s"Failed to drop course'$course' becasue of '$reason'")
									case _ => complete(HttpResponse(502, entity = "Something Wrong"))
								}
							case _ => complete(HttpResponse(504, entity = "The server was not able " + "to produce a timely response to your request.\r\nPlease try again in a short while!"))
						}
				})
			case POST -> Root / "quit" :? SidQueryParaMatcher(studentId) +& CidQueryParaMatcher(courseId) =>
				Ok({
					val studentID = studentId.toInt + 1
					val courseID = courseId.toInt
					implicit val askTimeout: Timeout = 10.seconds // set timeout
					onComplete((studentRegion ? Student.Envelope(studentID, Student.Quit(courseID))).mapTo[Student.Info]) {
						case Success(res) =>
							res match {
								case Student.Success(_, course, _) => complete(s"Successes drop course'$course'")
								case Student.Failure(_, course, _, reason) => complete(s"Failed to drop course'$course' becasue of '$reason'")
								case _ => complete(HttpResponse(502, entity = "Something Wrong"))
							}
						case _ => complete(HttpResponse(504, entity = "The server was not able " + "to produce a timely response to your request.\r\nPlease try again in a short while!"))
					}
				})
			case POST -> Root / "setlimit" :? CidQueryParaMatcher(courseId) +& SizeQueryParaMatcher(size) =>
				Ok({
					val courseID = courseId.toInt
						val lim = size.toInt
						implicit val askTimeout: Timeout = 10.seconds // set timeout
						onComplete((courseRegion ? Course.Envelope(courseID, Course.SetLimit(lim))).mapTo[Done]) {
							case _ : Success[Done]=> complete (HttpEntity (ContentTypes.`text/html(UTF-8)`, "<h1>Set Successfully!</h1>") )
							case _ => complete(HttpResponse(504, entity = "Timeout"))
						}
				})
			case POST -> Root / "table" :? SidQueryParaMatcher(studentId) =>
				Ok({
					val studentID = studentId.toInt + 1
					implicit val askTimeout: Timeout = 10.seconds // set timeout
					onComplete((studentRegion ? Student.Envelope(studentID, Student.Table())).mapTo[Student.Info]) {
						case Success(res) =>
							res match {
								case Student.Response(_, _, content) => complete(s"You have selected: '$content'")
								case _ => complete(HttpResponse(502, entity = "Something Wrong"))
							}
						case _ => complete(HttpResponse(504, entity = "The server was not able " + "to produce a timely response to your request.\r\nPlease try again in a short while!"))
					}
				})
		}
	}
}

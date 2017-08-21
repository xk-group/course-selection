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
import moe.taiho.course_selection.actors.CommonMessage.Pong

import scala.concurrent.Future
import scala.util.{Success, Try}

class Pinger (studentRegion: ActorRef, courseRegion: ActorRef){
	def pingUntilPong(reciver : String, id : Int): Unit = {
		print(s"\033[32m ${id}\033[0m")
		reciver match {
			case "student" =>
				implicit val askTimeout: Timeout = 10.seconds // set timeout
				(studentRegion ? Student.Envelope(id, Student.Ping())).mapTo[Pong] onComplete {
					case Success(_) => // do nothing
					case _ => pingUntilPong("student", id)
				}
			case "course" =>
				implicit val askTimeout: Timeout = 10.seconds // set timeout
				((courseRegion ? Course.Envelope(id, Course.Ping())).mapTo[Pong]) onComplete {
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

object HTTPServer {

	def run(studentRegion: ActorRef, courseRegion: ActorRef)(implicit system: ActorSystem): Future[ServerBinding] = {
		// Http route
		implicit val materializer = ActorMaterializer()
		// needed for the future flatMap/onComplete in the end
		implicit val executionContext = system.dispatcher

		val route : Route=
			path("hello") {
				get {
					complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Say hello to akka-http</h1>"))
				}
			} ~
			path("ping") {
				get {
					val t0 = System.nanoTime()
					val ping = new Pinger(studentRegion, courseRegion)
					ping.ping()
					val t1 = System.nanoTime()
					complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, s"<h1>ping all actor in ${(t1-t0)/1000000} ms</h1>"))
				}
			} ~
			path("demo") {
				post {
					implicit val askTimeout: Timeout = 10.seconds // set timeout
					onComplete((studentRegion ? Student.Envelope(10086, Student.DebugPrint("Debug Message"))).mapTo[String]) {
						case Success(result) => complete(result)
						case _ => complete(HttpResponse(504, entity = "The server was not able " + "to produce a timely response to your request.\r\nPlease try again in a short while!"))
					}
				}
			} ~
			path("take") {
				post {
					parameters('sid, 'cid) { (studentId, courseId) =>
						val studentID = studentId.toInt + 1
						val courseID = courseId.toInt
						implicit val askTimeout: Timeout = 10.seconds // set timeout
						onComplete((studentRegion ? Student.Envelope(studentID, Student.Take(courseID))).mapTo[Student.Info]) {
							case Success(res) =>
								res match {
									case Student.Success(_, course, _) => complete(s"Successes take course'$course'")
									case Student.Failure(_, course, _, reason) => complete(s"Failed to take course'$course' becasue of '$reason'")
									case _ => complete(HttpResponse(502, entity = "Something Wrong"))
								}
							case _ => complete(HttpResponse(504, entity = "The server was not able " + "to produce a timely response to your request.\r\nPlease try again in a short while!"))
						}
					}
				}
			} ~
			path("quit") {
				post {
					parameters('sid, 'cid) { (studentId, courseId) =>
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
					}
				}
			} /*~
			path("table") {
				post {
					parameters('sid) { (studentId) =>
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
					}
				}
			} ~
			path("setlimit") {
				post {
					parameters('cid, 'size) { (courseId, size) =>
						val courseID = courseId.toInt
						val lim = size.toInt
						implicit val askTimeout: Timeout = 10.seconds // set timeout
						onComplete((courseRegion ? Course.Envelope(courseID, Course.SetLimit(lim))).mapTo[Done]) {
							case _ : Success[Done]=> complete (HttpEntity (ContentTypes.`text/html(UTF-8)`, "<h1>Set Successfully!</h1>") )
							case _ => complete(HttpResponse(504, entity = "Timeout"))
						}
					}
				}
			}*/

		Http().bindAndHandle(route, "0.0.0.0", ConfigFactory.load().getInt("course-selection.http-port"))
	}
}


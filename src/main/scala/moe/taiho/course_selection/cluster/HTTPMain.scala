package moe.taiho.course_selection.cluster

import akka.actor.{ActorSystem}
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model._
import scala.concurrent.duration._
import akka.pattern.ask
import akka.cluster.sharding.{ClusterSharding}
import akka.util.Timeout

import moe.taiho.course_selection.actors.{Course, Student}


import scala.io.StdIn

object WebServer extends App {

	// Set up StudentRegion/ CourseRegion
	implicit val system = ActorSystem("CourseSelectSystem")

	val studentRegion = ClusterSharding(system).startProxy(
		Student.ShardName, Student.Role,
		Student.extractEntityId, Student.extractShardId
	)

	val courseRegion = ClusterSharding(system).startProxy(
		Course.ShardName, Course.Role,
		Course.extractEntityId, Course.extractShardId
	)

	// set up Listener
	// system.actorOf(Props[NaiveClusterListener])

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
			path("demo") {
				get {
					implicit val askTimeout: Timeout = 3.seconds // set timeout
					onSuccess((studentRegion ? Student.Envelope(10086, Student.DebugPrint("Debug Message"))).mapTo[String]) { result =>
						complete(result)
					}
				}
			} ~
			path("take") {
				get {
					parameters('sid, 'cid) { (studentId, courseId) =>
						val studentID = studentId.toInt + 1
						val courseID = courseId.toInt
						implicit val askTimeout: Timeout = 3.seconds // set timeout
						onSuccess((studentRegion ? Student.Envelope(studentID, Student.Take(courseID))).mapTo[Student.Info]) { res =>
							res match {
								case Student.Success(_, course, _) => complete(s"Successes take course'$course'")
								case Student.Failure(_, course, _, reason) => complete(s"Failed to take course'$course' becasue of '$reason'")
								case _ => complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Something Wrong</h1>"))
							}
						}
					}
				}
			} ~
			path("quit") {
				get {
					parameters('sid, 'cid) { (studentId, courseId) =>
						val studentID = studentId.toInt + 1
						val courseID = courseId.toInt
						implicit val askTimeout: Timeout = 3.seconds // set timeout
						onSuccess((studentRegion ? Student.Envelope(studentID, Student.Quit(courseID))).mapTo[Student.Info]) { res =>
							res match {
								case Student.Success(_, course, _) => complete(s"Successes drop course'$course'")
								case Student.Failure(_, course, _, reason) => complete(s"Failed to drop course'$course' becasue of '$reason'")
								case _ => complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Something Wrong</h1>"))
							}
						}
					}
				}
			} ~
			path("table") {
				get {
					parameters('sid) { (studentId) =>
						val studentID = studentId.toInt + 1
						implicit val askTimeout: Timeout = 3.seconds // set timeout
						onSuccess((studentRegion ? Student.Envelope(studentID, Student.Table())).mapTo[Student.Info]) { res =>
							res match {
								case Student.Response(_, _, content) => complete(s"You have selected: '$content'")
								case _ => complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Something Wrong</h1>"))
							}
						}
					}
				}
			} ~
			path("setlimit") {
				get {
					parameters('cid, 'size) { (courseId, size) =>
						val courseID = courseId.toInt
						val lim = size.toInt
						implicit val askTimeout: Timeout = 3.seconds // set timeout
						courseRegion ! Course.Envelope(courseID, Course.SetLimit(lim))
						complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Set Successfully!</h1>"))
					}
				}
			}

	val bindingFuture = Http().bindAndHandle(route, "localhost", 8000)

	println(s"Server online at http://localhost:8000/\nPress RETURN to stop...")
	StdIn.readLine() // let it run until user presses return
	bindingFuture
		.flatMap(_.unbind()) // trigger unbinding from the port
		.onComplete(_ => system.terminate()) // and shutdown when done
}


package moe.taiho.course_selection.cluster

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import akka.Done
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import akka.http.scaladsl.model._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import akka.pattern.ask
import akka.pattern.after
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.cluster.ddata.Replicator.{Changed, Subscribe}
import akka.cluster.ddata.{DistributedData, LWWMapKey}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.util.Timeout

import moe.taiho.course_selection.actors.{Course, Student}


import scala.io.StdIn

object WebServer extends App {

  // Set up StudentRegion/ CourseRegion
    implicit val system = ActorSystem("CourseSelectSystem")
    val cluster = Cluster(system)

    val studentRegion = if (cluster.selfRoles contains "student") {
        ClusterSharding(system).start(
            Student.ShardName, Props[Student], ClusterShardingSettings(system).withRole(Student.Role),
            Student.extractEntityId, Student.extractShardId
        )
    } else {
        ClusterSharding(system).startProxy(
            Student.ShardName, Student.Role,
            Student.extractEntityId, Student.extractShardId
        )
    }

    val courseRegion = if (cluster.selfRoles contains "course") {
        ClusterSharding(system).start(
            Course.ShardName, Props[Course], ClusterShardingSettings(system).withRole(Course.Role),
            Course.extractEntityId, Course.extractShardId
        )
    } else {
        ClusterSharding(system).startProxy(
            Course.ShardName, Course.Role,
            Course.extractEntityId, Course.extractShardId
        )
    }

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
            val courseID = courseId.toInt + 1
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
      path("drop") {
        get {
          parameters('sid, 'cid) { (studentId, courseId) =>
            val studentID = studentId.toInt + 1
            val courseID = courseId.toInt + 1
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
/*
      path("table") {
        get {
          parameters('sid) { (studentId) =>
            val studentID = studentId.toInt + 1
            implicit val askTimeout: Timeout = 3.seconds // set timeout
            onSuccess((studentRegion ? Student.Envelope(studentID, Student.Table())).mapTo[Student.Info]) { res =>
              res match {
                case Student.Success(_, course, _) => complete(s"Successes drop course'$course'")
                case Student.Failure(_, course, _, reason) => complete(s"Failed to drop course'$course' becasue of '$reason'")
                case _ => complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Something Wrong</h1>"))
              }
            }
          }
        }
      } ~
*/
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

/*
class NaiveClusterListener extends Actor with ActorLogging {
  val replicator = DistributedData(context.system).replicator
  implicit val node = Cluster(context.system)

  val SharedDataKey = LWWMapKey[Int, Int]("Course")

  override def preStart(): Unit = {
    super.preStart()
    node.subscribe(self, initialStateMode = InitialStateAsEvents, classOf[MemberEvent], classOf[UnreachableMember])
    replicator ! Subscribe(SharedDataKey, self)
  }
  override def postStop(): Unit = node.unsubscribe(self)

  case class Tick()
  var total = 0
  val tickTask = context.system.scheduler.schedule(5.seconds, 5.seconds, self, Tick)

  override def receive: Receive = {
    case MemberUp(member) =>
      log.info("Member is Up: {}", member.address)
    case UnreachableMember(member) =>
      log.info("Member detected as unreachable: {}", member)
    case MemberRemoved(member, previousStatus) =>
      log.info(
        "Member is Removed: {} after {}",
        member.address, previousStatus)
    case _: MemberEvent => // ignore
    case c @ Changed(SharedDataKey) =>
      total = c.get(SharedDataKey).entries.values.sum
    case Tick => log.info(s"course all selected: ${total}")
  }
}
*/

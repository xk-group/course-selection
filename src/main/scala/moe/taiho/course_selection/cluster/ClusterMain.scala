package moe.taiho.course_selection.cluster

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.cluster.ddata.Replicator.{Changed, Subscribe}
import akka.cluster.ddata.{DistributedData, LWWMapKey}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import moe.taiho.course_selection.actors.{Course, Student}

import com.typesafe.config.ConfigFactory

object ClusterMain extends App {
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

    if (cluster.selfRoles contains "http") {
        val bindingFuture = HTTPServer.run(studentRegion, courseRegion)
        val http_port = ConfigFactory.load().getInt("course-selection.http-port")
        println(s"Server online at http://0.0.0.0:${http_port}/\nPress RETURN to stop...")
    }

    system.actorOf(Props[NaiveClusterListener])
}

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
            log.info("\033[31mMember is Up: {}\033[0m", member.address)
        case UnreachableMember(member) =>
            log.info("\033[31mMember detected as unreachable: {}\033[0m", member)
        case MemberRemoved(member, previousStatus) =>
            log.info(
                "\033[31mMember is Removed: {} after {}\033[0m",
                member.address, previousStatus)
        case _: MemberEvent => // ignore
        case c @ Changed(SharedDataKey) =>
            total = c.get(SharedDataKey).entries.values.sum
        case Tick => log.info(s"course all selected: ${total}")
    }
}

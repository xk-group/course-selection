package moe.taiho.course_selection.cluster

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.pattern.ask
import akka.pattern.after
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.cluster.ddata.Replicator.{Changed, Subscribe}
import akka.cluster.ddata.{DistributedData, LWWMapKey}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.util.Timeout
import moe.taiho.course_selection.actors.{Course, Student}

import scala.concurrent.Future
import scala.util.{Failure, Success}

object ClusterMain extends App {
    val system = ActorSystem("CourseSelectSystem")
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

    implicit val timeout = Timeout(5 seconds)

    if (cluster.selfRoles contains "node1") {
        system.actorOf(Props[NaiveClusterListener])
        courseRegion ! Course.Envelope(1, Course.SetLimit(100))
        after(15 seconds, using = system.scheduler) {
            studentRegion ? Student.Envelope(id = 1, Student.Take(1))
        } onComplete {
            case Success(_: Student.Success) => println("success")
        }
        after(30 seconds, using = system.scheduler) {
            for (c <- 1 to 2000) courseRegion ! Course.Envelope(c, Course.SetLimit(500))
            Future()
        }
        after(60 seconds, using = system.scheduler) {
            for (s <- 1 to 10000; c <- 1 to 30) studentRegion ! Student.Envelope(id = s, Student.Take((s.hashCode+c).hashCode%2000+1))
            Future()
        }
    }
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

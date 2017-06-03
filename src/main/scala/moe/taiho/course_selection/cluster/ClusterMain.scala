package moe.taiho.course_selection.cluster

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}

import moe.taiho.course_selection.actors.{Course, Student}

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

    if (cluster.selfRoles contains "node1") {
        system.actorOf(Props[NaiveClusterListener])
        courseRegion ! Course.Envelope(1, Course.SetLimit(30))
    }

}

class NaiveClusterListener extends Actor with ActorLogging {
    val cluster = Cluster(context.system)

    override def preStart(): Unit = {
        super.preStart()
        cluster.subscribe(self, initialStateMode = InitialStateAsEvents, classOf[MemberEvent], classOf[UnreachableMember])
    }
    override def postStop(): Unit = cluster.unsubscribe(self)

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
    }
}

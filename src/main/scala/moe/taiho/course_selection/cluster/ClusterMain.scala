package moe.taiho.course_selection.cluster

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings, ClusterSingletonProxy, ClusterSingletonProxySettings}
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import moe.taiho.course_selection.actors.{CourseActor, StudentActor}
import moe.taiho.course_selection.cluster.ClusterMain.system

object ClusterMain extends App {
    val system = ActorSystem("CourseSelectSystem")
    val cluster = Cluster(system)

    val studentRegion = if (cluster.selfRoles contains "student") {
        ClusterSharding(system).start(
            StudentActor.ShardName, Props[StudentActor], ClusterShardingSettings(system).withRole(StudentActor.Role),
            StudentActor.extractEntityId, StudentActor.extractShardId
        )
    } else {
        ClusterSharding(system).startProxy(
            StudentActor.ShardName, StudentActor.Role,
            StudentActor.extractEntityId, StudentActor.extractShardId
        )
    }

    val courseRegion = if (cluster.selfRoles contains "course") {
        ClusterSharding(system).start(
            CourseActor.ShardName, Props[CourseActor], ClusterShardingSettings(system).withRole(CourseActor.Role),
            CourseActor.extractEntityId, CourseActor.extractShardId
        )
    } else {
        ClusterSharding(system).startProxy(
            CourseActor.ShardName, CourseActor.Role,
            CourseActor.extractEntityId, CourseActor.extractShardId
        )
    }

    if (cluster.selfRoles contains "node1") {
        system.actorOf(Props[NaiveClusterListener])
    }

}

class NaiveClusterListener extends Actor with ActorLogging {
    val cluster = Cluster(context.system)

    override def preStart(): Unit = {
        super.preStart()
        cluster.subscribe(self, initialStateMode = InitialStateAsEvents, classOf[MemberEvent], classOf[UnreachableMember])
    }
    override def postStop(): Unit = cluster.unsubscribe(self)

    override def receive = {
        case MemberUp(member) =>
            log.info("Member is Up: {}", member.address)
            if (member.roles contains "course") {
                val courseRegion = ClusterSharding(system).shardRegion(CourseActor.ShardName)
                courseRegion ! CourseActor.Envelope(1, CourseActor.SetLimit(30))
            }
        case UnreachableMember(member) =>
            log.info("Member detected as unreachable: {}", member)
        case MemberRemoved(member, previousStatus) =>
            log.info(
                "Member is Removed: {} after {}",
                member.address, previousStatus)
        case _: MemberEvent => // ignore
    }
}

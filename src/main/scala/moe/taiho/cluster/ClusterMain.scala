package moe.taiho.cluster

import akka.actor.{ActorSystem, Props}
import akka.cluster.Cluster
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import moe.taiho.actors.{CourseActor, NaiveClusterListener, StudentActor}

object ClusterMain extends App {
    val system = ActorSystem("CourseSelectSystem")
    val cluster = Cluster(system)
    if (cluster.selfRoles contains "node1") {
        system.actorOf(Props[NaiveClusterListener])
        val studentRegion = ClusterSharding(system).start(
            StudentActor.ShardName, Props[StudentActor], ClusterShardingSettings(system),
            StudentActor.extractEntityId, StudentActor.extractShardId(100)
        )
        val courseRegion = ClusterSharding(system).startProxy(
            CourseActor.ShardName, Some("node2"),
            CourseActor.extractEntityId, CourseActor.extractShardId(100)
        )
    } else if (cluster.selfRoles contains "node2") {
        val studentRegion = ClusterSharding(system).startProxy(
            StudentActor.ShardName, Some("node1"),
            StudentActor.extractEntityId, StudentActor.extractShardId(100)
        )
        val courseRegion = ClusterSharding(system).start(
            CourseActor.ShardName, Props[CourseActor], ClusterShardingSettings(system),
            CourseActor.extractEntityId, CourseActor.extractShardId(100)
        )
    }
}

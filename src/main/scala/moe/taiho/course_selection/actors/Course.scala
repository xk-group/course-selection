package moe.taiho.course_selection.actors

import akka.Done
import akka.cluster.Cluster
import akka.cluster.ddata.Replicator.{Update, UpdateResponse, WriteLocal}
import akka.cluster.ddata.{DistributedData, LWWMap, LWWMapKey}
import akka.cluster.sharding.ShardRegion
import akka.event.Logging
import akka.persistence.{PersistentActor, RecoveryCompleted}
import com.typesafe.config.ConfigFactory
import moe.taiho.course_selection.KryoSerializable
import moe.taiho.course_selection.actors.CommonMessage.Reason

import scala.collection.mutable

object Course {
    sealed trait Command extends KryoSerializable
    case class SetLimit(num: Int) extends Command
    case class Take(student: Int, deliveryId: Long) extends Command
    case class Quit(student: Int, deliveryId: Long) extends Command

    case class Envelope(id: Int, command: Command) extends KryoSerializable

    val ShardNr: Int = ConfigFactory.load().getInt("course-selection.course-shard-nr")
    val ShardName = "Course"
    val Role = Some("course")
    val extractEntityId: ShardRegion.ExtractEntityId = {
        case Envelope(id: Int, command: Command) => (id.toString, command)
    }
    val extractShardId: ShardRegion.ExtractShardId = {
        case m: Envelope => (m.id.hashCode % ShardNr).toString
        case ShardRegion.StartEntity(id) => (id.toInt.hashCode % ShardNr).toString
    }

    case class CourseFull() extends Reason
}

class Course extends PersistentActor {
    import Course._

    val id: Int = self.path.name.toInt

    val log = Logging(context.system, this)

    val replicator = DistributedData(context.system).replicator
    implicit val node = Cluster(context.system)

    val SharedDataKey = LWWMapKey[Int, Int]("Course")

    class State {
        private[Course] var limit = 0
        private[Course] var numSelected = 0
        private val selected: mutable.TreeMap[Int, (/*status*/Boolean, /*deliveryId*/Long)] = mutable.TreeMap()

        def isFull: Boolean = numSelected >= limit
        def newer(student: Int, deliveryId: Long): Boolean = !(selected contains student) || selected(student)._2 < deliveryId

        def update(m: Command): Unit = {
            m match {
                case SetLimit(num) => limit = num
                case Take(student, deliveryId) =>
                    assert(newer(student, deliveryId))
                    selected(student) = (true, deliveryId)
                    numSelected += 1
                case Quit(student, deliveryId) =>
                    assert(newer(student, deliveryId))
                    selected(student) = (false, deliveryId)
                    numSelected -= 1
            }
        }
    }

    var state: State = new State()

    override def receiveRecover: Receive = {
        case m: Command => state.update(m)
        case RecoveryCompleted => replicator ! Update(SharedDataKey, LWWMap.empty[Int, Int], WriteLocal)(_ + (id, state.numSelected))
    }

    override def receiveCommand: Receive = {
        case m @ Take(student, deliveryId) =>
            if (state.newer(student, deliveryId)) {
                // todo: do some check here
                if (!state.isFull) persist(m) { m =>
                    state.update(m)
                    replicator ! Update(SharedDataKey, LWWMap.empty[Int, Int], WriteLocal)(_ + (id, state.numSelected))
                    //log.info(s"\033[32m ${student} take ${id}\033[0m")
                    sender() ! Student.Taken(id, deliveryId)
                } else {
                    sender() ! Student.Rejected(id, deliveryId, CourseFull())
                }
            }
        case m @ Quit(student, deliveryId) =>
            if (state.newer(student, deliveryId)) persist(m) { m =>
                state.update(m)
                replicator ! Update(SharedDataKey, LWWMap.empty[Int, Int], WriteLocal)(_ + (id, state.numSelected))
                //log.info(s"\033[32m ${student} quit ${id}\033[0m")
                sender() ! Student.Quitted(id, deliveryId)
            }
        case m @ SetLimit(_) => 
            val t0 = System.nanoTime()
            persist(m) {
                m => state.update(m)
                val t1 = System.nanoTime()
                log.info(s"\033[32mset limit ${m.num} time ${(t1-t0)/1000000} ms\033[0m")
            }
	        sender() ! Done
        case _: UpdateResponse[_] => // ignore
        case _ => log.warning(s"\033[32munhandled message on Course $id\033[0m")
    }

    override def persistenceId: String = s"Course-$id"
}

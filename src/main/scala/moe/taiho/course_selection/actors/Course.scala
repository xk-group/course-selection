package moe.taiho.course_selection.actors

import akka.Done
import akka.actor.ActorRef
import akka.cluster.Cluster
import akka.cluster.ddata.Replicator.{Update, UpdateResponse, WriteLocal}
import akka.cluster.ddata.{DistributedData, LWWMap, LWWMapKey}
import akka.cluster.sharding.ShardRegion
import akka.event.Logging
import akka.persistence.{PersistentActor, RecoveryCompleted}
import com.typesafe.config.ConfigFactory
import moe.taiho.course_selection.KryoSerializable
import moe.taiho.course_selection.actors.CommonMessage.{Pong}
import moe.taiho.course_selection.policies.CoursePolicy

import scala.collection.mutable

object Course {
    sealed trait Command extends KryoSerializable
    case class Ping() extends Command
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
}

class Course extends PersistentActor {
    import Course._

    val id: Int = self.path.name.toInt

    val log = Logging(context.system, this)

    val replicator: ActorRef = DistributedData(context.system).replicator
    implicit val node = Cluster(context.system)

    val SharedDataKey: LWWMapKey[Int, Int] = LWWMapKey[Int, Int]("Course")

    val policy: CoursePolicy = new CoursePolicy(id)

    class State {
        final case class StudentRecord(status: Boolean, deliveryId: Long)

        private val selected: mutable.TreeMap[Int, StudentRecord] = mutable.TreeMap()
        private var _numSelected: Int = 0
        def numSelected(): Int = _numSelected

        def newer(student: Int, deliveryId: Long): Boolean =
            !(selected contains student) || selected(student).deliveryId < deliveryId

        def update(m: Command): Unit = {
            m match {
                case Take(student, deliveryId) =>
                    assert(newer(student, deliveryId))
                    selected(student) = StudentRecord(true, deliveryId)
                    _numSelected += 1
                case Quit(student, deliveryId) =>
                    assert(newer(student, deliveryId))
                    selected(student) = StudentRecord(false, deliveryId)
                    _numSelected -= 1
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
                val validation = policy.validateTake(student)
                if (validation.isEmpty) persist(m) { m =>
                    state.update(m)
                    replicator ! Update(SharedDataKey, LWWMap.empty[Int, Int], WriteLocal)(_ + (id, state.numSelected))
                    sender() ! Student.Taken(id, deliveryId)
                    policy.take(student)
                    //log.info(s"\033[32m ${student} take ${id}\033[0m")
                } else {
                    sender() ! Student.Rejected(id, deliveryId, validation.get)
                }
            }
        case m @ Quit(student, deliveryId) =>
            if (state.newer(student, deliveryId)) {
                persist(m) { m =>
                    state.update(m)
                    replicator ! Update(SharedDataKey, LWWMap.empty[Int, Int], WriteLocal)(_ + (id, state.numSelected))
                    sender() ! Student.Quitted(id, deliveryId)
                    policy.drop(student)
                //log.info(s"\033[32m ${student} quit ${id}\033[0m")
                }
            }
        case m: CoursePolicy.Command =>
            policy.setPolicy(m)
            sender() ! Done
        case _: Ping => sender() ! Pong()
        case _: UpdateResponse[_] => // ignore
        case _ => log.warning(s"\033[32munhandled message on Course $id\033[0m")
    }

    override def persistenceId: String = s"Course-$id"

    /*
    override def preStart(): Unit = {
	    log.warning(s"\033[32m ${id} is up! \033[0m")
    }*/
}

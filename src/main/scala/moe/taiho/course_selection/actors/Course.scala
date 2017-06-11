package moe.taiho.course_selection.actors

import akka.cluster.sharding.ShardRegion
import akka.event.Logging
import akka.persistence.{PersistentActor, SnapshotOffer}
import com.typesafe.config.ConfigFactory
import moe.taiho.course_selection.actors.CommonMessage.Reason

import scala.collection.mutable

object Course {

    sealed trait Command
    case class SetLimit(num: Int) extends Command
    case class Take(student: Int, deliveryId: Long) extends Command
    case class Drop(student: Int, deliveryId: Long) extends Command

    case class Envelope(id: Int, command: Command)

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

    class State {
        private var limit = 0
        private var numSelected = 0
        private val selected: mutable.TreeMap[Int, (/*status*/Boolean, /*deliveryId*/Long)] = mutable.TreeMap()

        def isFull: Boolean = numSelected >= limit
        def newer(student: Int, deliveryId: Long): Boolean = !(selected contains student) || selected(student)._2 < deliveryId

        def update(m: Command): Unit = {
            m match {
                case SetLimit(num) => limit = num
                case Take(student, deliveryId) =>
                    assert(newer(student, deliveryId))
                    selected(student) = (true, deliveryId)
                case Drop(student, deliveryId) =>
                    assert(newer(student, deliveryId))
                    selected(student) = (false, deliveryId)
            }
        }
    }

    var state: State = new State()

    override def receiveRecover: Receive = {
        case m: Command => state.update(m)
    }

    override def receiveCommand: Receive = {
        case m @ Take(student, deliveryId) =>
            if (state.newer(student, deliveryId)) {
                // todo: do some check here
                if (!state.isFull) persist(m) { m =>
                    state.update(m)
                    log.warning("Taken")
                    //sender() ! Student.Envelope(student, Student.Taken(id, deliveryId))
                    sender() ! Student.Taken(id, deliveryId)
                } else {
                    log.warning("Refuse")
                    //sender() ! Student.Envelope(student, Student.Refused(id, deliveryId, CourseFull()))
                    sender() ! Student.Refused(id, deliveryId, CourseFull())
                }
            }
        case m @ Drop(student, deliveryId) =>
            if (state.newer(student, deliveryId)) persist(m) { m =>
                state.update(m)
                log.warning("Drope")
                //sender() ! Student.Envelope(student, Student.Dropped(id, deliveryId))
                sender() ! Student.Dropped(id, deliveryId)
            }
        case m @ SetLimit(_) => persist(m) {
            m => state.update(m)
        }
        //case m @ DebugPrint(msg) => log.warning(msg + id)
        case _ => log.warning(s"unhandled message on Course $id")
    }

    override def persistenceId: String = s"Course-$id"
}

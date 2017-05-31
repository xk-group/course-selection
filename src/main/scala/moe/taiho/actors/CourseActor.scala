package moe.taiho.actors

import akka.cluster.sharding.ShardRegion
import akka.event.Logging
import akka.persistence.{PersistentActor, SnapshotOffer}

import scala.collection.mutable

object CourseActor {

    sealed trait Command
    case class SetLimit(num: Int) extends Command
    case class Take(student: Int, deliveryId: Long) extends Command
    case class Drop(student: Int, deliveryId: Long) extends Command

    case class Envelope(id: Int, command: Command)

    val ShardName = "Course"
    val extractEntityId: ShardRegion.ExtractEntityId = {
        case Envelope(id: Int, command: Command) => (id.toString, command)
    }
    def extractShardId(numShards: Int): ShardRegion.ExtractShardId = {
        case m: Envelope => (m.id.hashCode % numShards).toString
        case ShardRegion.StartEntity(id) => (id.toInt.hashCode % numShards).toString
    }
}

class CourseActor extends PersistentActor {
    import CourseActor._

    val id = self.path.name.toInt

    val log = Logging(context.system, this)

    class State {
        private var limit = 0
        private var numSelected = 0
        private val selected: mutable.TreeMap[Int, (Boolean, Long)] = mutable.TreeMap()

        def hasStudent(student: Int): Boolean = selected contains student
        def isFull: Boolean = numSelected >= limit
        def newer(student: Int, deliveryId: Long): Boolean = !hasStudent(student) || selected(student)._2 < deliveryId

        def update(m: Command): Unit = {
            m match {
                case SetLimit(num) => limit = num
                case Take(student, deliveryId) => {
                    assert(newer(student, deliveryId))
                    selected(student) = (true, deliveryId)
                }
                case Drop(student, deliveryId) => {
                    assert(newer(student, deliveryId))
                    selected(student) = (false, deliveryId)
                }
            }
        }
    }

    var state: State = new State()

    override def receiveRecover: Receive = {
        case m: Command => state.update(m)
    }

    override def receiveCommand: Receive = {
        case m @ Take(student, deliveryId) => {
            if (state.newer(student, deliveryId)) {
                if (!state.isFull) persist(m) { m =>
                    state.update(m)
                    sender() ! StudentActor.Envelope(student, StudentActor.Taken(id, deliveryId))
                } else {
                    sender() ! StudentActor.Envelope(student, StudentActor.Full(id, deliveryId))
                }
            }
        }
        case m @ Drop(student, deliveryId) => {
            if (state.newer(student, deliveryId)) persist(m) { m =>
                state.update(m)
                sender() ! StudentActor.Envelope(student, StudentActor.Dropped(id, deliveryId))
            }
        }
        case _ => log.info(s"unhandled message on Course $id")
    }

    override def persistenceId: String = s"Course-$id"
}

package moe.taiho.course_selection.actors

import akka.actor.ActorRef
import akka.cluster.sharding.{ClusterSharding, ShardRegion}
import akka.event.Logging
import akka.persistence.{AtLeastOnceDelivery, PersistentActor}
import com.typesafe.config.ConfigFactory

import scala.collection.mutable

import CommonMessage.Reason

object Student {
    sealed trait Command
    // Requested by frontend
    case class Take(course: Int) extends Command
    case class Drop(course: Int) extends Command
    // Course responses
    case class Taken(course: Int, deliveryId: Long) extends Command
    case class Refused(course: Int, deliveryId: Long, reason: Reason) extends Command
    case class Dropped(course: Int, deliveryId: Long) extends Command

    case class Envelope(id: Int, command: Command)

    // Respond to frontend
    case class Success(student: Int, course: Int, target: Boolean)
    case class Failure(student: Int, course: Int, target: Boolean, reason: Reason)

    val ShardNr: Int = ConfigFactory.load().getInt("course-selection.student-shard-nr")
    val ShardName = "Student"
    val Role = Some("student")
    val extractEntityId: ShardRegion.ExtractEntityId = {
        case Envelope(id: Int, command: Command) => (id.toString, command)
    }
    val extractShardId: ShardRegion.ExtractShardId = {
        case m: Envelope => (m.id.hashCode % ShardNr).toString
        case ShardRegion.StartEntity(id) => (id.toInt.hashCode % ShardNr).toString
    }
}

class Student extends PersistentActor with AtLeastOnceDelivery {
    import Student._

    val log = Logging(context.system, this)

    val id: Int = self.path.name.toInt

    val courseRegion: ActorRef = ClusterSharding(context.system).shardRegion(Course.ShardName)

    class State {
        private val selected: mutable.Map[Int, (/*target*/Boolean, /*confirmed*/Boolean, /*deliveryId*/Long)] = mutable.TreeMap()

        def effective(course: Int, deliveryId: Long): Boolean = {
            selected get course exists { t =>
                assert(deliveryId <= t._3)
                !t._2 && deliveryId == t._3
            }
        }

        def update(m: Command): Unit = {
            m match {
                case Taken(course, deliveryId) =>
                    assert(effective(course, deliveryId))
                    confirmDelivery(deliveryId)
                    selected(course) = (true, true, deliveryId)
                case Refused(course, deliveryId, _) =>
                    assert(effective(course, deliveryId))
                    confirmDelivery(deliveryId)
                    selected remove course
                case Dropped(course, deliveryId) =>
                    assert(effective(course, deliveryId))
                    confirmDelivery(deliveryId)
                    selected.remove(course)
                case Take(course) => deliver(courseRegion.path) {
                    deliveryId =>
                        selected get course foreach { t => if (!t._2) confirmDelivery(t._3) }
                        selected(course) = (true, false, deliveryId)
                        Course.Envelope(course, Course.Take(student = id, deliveryId))
                }
                case Drop(course) => deliver(courseRegion.path) {
                    deliveryId =>
                        selected get course foreach { t => if (!t._2) confirmDelivery(t._3) }
                        selected(course) = (false, false, deliveryId)
                        Course.Envelope(course, Course.Drop(student = id, deliveryId))
                }
            }
        }

        def query(course: Int): (Boolean, Boolean) = selected get course map { t => (t._1, t._2) } getOrElse (false, true)
    }

    var state = new State()

    val sessions: mutable.Map[Int, ActorRef] = mutable.TreeMap()

    override def receiveRecover: Receive = {
        case m: Command => state.update(m)
    }

    override def receiveCommand: Receive = {
        case m @ Taken(course, deliveryId) =>
            if (state.effective(course, deliveryId)) persist(m) { m =>
                state.update(m)
                sessions get course foreach { s =>
                    s ! Success(student = id, course, target = true)
                    sessions remove course
                }
            }
        case m @ Refused(course, deliveryId, reason) =>
            if (state.effective(course, deliveryId)) persist(m) { m =>
                state.update(m)
                sessions get course foreach { s =>
                    s ! Failure(student = id, course, target = false, reason)
                    sessions remove course
                }
            }
        case m @ Dropped(course, deliveryId) =>
            if (state.effective(course, deliveryId)) persist(m) { m =>
                state.update(m)
                sessions get course foreach { s =>
                    s ! Success(student = id, course, target = false)
                    sessions remove course
                }
            }
        case m @ Take(course) =>
            // todo: do some check here!
            state.query(course) match {
                case (true, false) => // do nothing
                case (true, true) => sender() ! Success(student = id, course, target = true)
                case _ => persist(m) { m =>
                    sessions(course) = sender()
                    state.update(m)
                }
            }
        case m @ Drop(course) =>
            state.query(course) match {
                case (false, false) => // do nothing
                case (false, true) => sender() ! Success(student = id, course, target = false)
                case _ => persist(m) { m =>
                    sessions(course) = sender()
                    state.update(m)
                }
            }
        case _ => log.warning("unhandled message")
    }

    override def persistenceId: String = s"Student-$id"
}

package moe.taiho.course_selection.actors

import akka.Done
import akka.actor.ActorRef
import akka.cluster.sharding.{ClusterSharding, ShardRegion}
import akka.event.Logging
import akka.persistence.{AtLeastOnceDelivery, PersistentActor}
import com.typesafe.config.ConfigFactory

import scala.collection.mutable
import CommonMessage.{Ping, Pong, Reason}
import akka.persistence.AtLeastOnceDelivery.UnconfirmedWarning
import moe.taiho.course_selection.KryoSerializable
import moe.taiho.course_selection.policies.StudentPolicy

object Student {
    sealed trait Command extends KryoSerializable
    // Requested by frontend
    case class Take(course: Int) extends Command
    case class Quit(course: Int) extends Command
    case class Table() extends Command
    // Course responses
    case class Taken(course: Int, deliveryId: Long) extends Command
    case class Rejected(course: Int, deliveryId: Long, reason: Reason) extends Command
    case class Quitted(course: Int, deliveryId: Long) extends Command
    case class DebugPrint(msg: String) extends Command

    case class Envelope(id: Int, command: Command) extends KryoSerializable

    // Respond to frontend
    sealed trait Info extends KryoSerializable
    case class Success(student: Int, course: Int, target: Boolean) extends Info
    case class Failure(student: Int, course: Int, target: Boolean, reason: Reason) extends Info

    case class StateInfo(student: Int, course: Int, content: Array[(Int, Boolean, Boolean)]) extends Info

    case class DupRequest() extends Reason {
        override def message(): String = "New request with the same course"
    }

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

    val policy: StudentPolicy = new StudentPolicy(id)

    class State {
        final case class CourseRecord(target: Boolean, confirmed: Boolean, deliveryId: Long)

        private val selected: mutable.Map[Int, CourseRecord] = mutable.TreeMap()

        // make sure that the result from the course is related to our last request
        def effective(course: Int, deliveryId: Long): Boolean = {
            selected get course exists { t =>
                assert(deliveryId <= t.deliveryId)
                !t.confirmed && deliveryId == t.deliveryId
            }
        }

        def update(m: Command): Unit = {
            m match {
                case Taken(course, deliveryId) =>
                    assert(effective(course, deliveryId))
                    confirmDelivery(deliveryId)
                    selected(course) = CourseRecord(true, true, deliveryId)
                case Rejected(course, deliveryId, _) =>
                    assert(effective(course, deliveryId))
                    confirmDelivery(deliveryId)
                    selected remove course
                case Quitted(course, deliveryId) =>
                    assert(effective(course, deliveryId))
                    confirmDelivery(deliveryId)
                    selected.remove(course)
                case Take(course) => deliver(courseRegion.path) {
                    deliveryId =>
                        selected get course foreach { t => if (!t.confirmed) confirmDelivery(t.deliveryId) }
                        selected(course) = CourseRecord(true, false, deliveryId)
                        Course.Envelope(course, Course.Take(student = id, deliveryId))
                }
                case Quit(course) => deliver(courseRegion.path) {
                    deliveryId =>
                        selected get course foreach { t => if (!t.confirmed) confirmDelivery(t.deliveryId) }
                        selected(course) = CourseRecord(false, false, deliveryId)
                        Course.Envelope(course, Course.Quit(student = id, deliveryId))
                }
            }
        }

        def query(course: Int): (Boolean, Boolean) = selected get course map { t => (t.target, t.confirmed) } getOrElse (false, true)

        def list(): Array[(Int, Boolean, Boolean)] =
            selected map { case (course, record) => (course, record.target, record.confirmed) } toArray
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
                sessions remove course foreach { s => s ! Success(student = id, course, target = true) }
                policy.take(course)
                //log.info(s"\033[32m ${id} take ${course}\033[0m")
            }
        case m @ Rejected(course, deliveryId, reason) =>
            if (state.effective(course, deliveryId)) persist(m) { m =>
                state.update(m)
                sessions remove course foreach { s => s ! Failure(student = id, course, target = false, reason) }
                policy.failedTake(course)
            }
        case m @ Quitted(course, deliveryId) =>
            if (state.effective(course, deliveryId)) persist(m) { m =>
                state.update(m)
                sessions remove course foreach { s => s ! Success(student = id, course, target = false) }
                policy.drop(course)
                //log.info(s"\033[32m ${id} quit ${course}\033[0m")
            }
        case m @ Take(course) =>
            sessions remove course foreach { s => s ! Failure(student = id, course, target = true, DupRequest()) }
            val validation = policy.validateTake(course)
            if (validation.isEmpty) {
                state.query(course) match {
                    case (true, false) => // do nothing
                    case (true, true) => sender() ! Success(student = id, course, target = true)
                    case _ => persist(m) { m =>
                        sessions(course) = sender()
                        state.update(m)
                        policy.preTake(course)
                    }
                }
            } else {
                sender() ! Failure(student = id, course, target = true, validation.get)
            }
        case m @ Quit(course) =>
            sessions remove course foreach { s => s ! Failure(student = id, course, target = true, DupRequest()) }
            state.query(course) match {
                case (false, false) => // do nothing
                case (false, true) => sender() ! Success(student = id, course, target = false)
                case _ => persist(m) { m =>
                    sessions(course) = sender()
                    state.update(m)
                    policy.preDrop(course)
                }
            }
        case m @ Table() => sender() ! StateInfo(student = id, course = 0, content = state.list())
        case m: StudentPolicy.Command =>
            policy.setPolicy(m)
            sender() ! Done
        case _: Ping => sender() ! Pong()
        case _: UnconfirmedWarning => // ignore
        case m => log.warning(s"\033[31munhandled message $m\033[0m")
    }

    override def persistenceId: String = s"Student-$id"

    override def preStart(): Unit = {
        log.warning(s"\033[32m ${id} is up! \033[0m")
    }
}

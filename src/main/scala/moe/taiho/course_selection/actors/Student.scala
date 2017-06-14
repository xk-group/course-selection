package moe.taiho.course_selection.actors

import akka.actor.ActorRef
import akka.cluster.sharding.{ClusterSharding, ShardRegion}
import akka.event.Logging
import akka.persistence.{AtLeastOnceDelivery, PersistentActor}
import com.typesafe.config.ConfigFactory

import scala.collection.mutable
import CommonMessage.Reason
import akka.persistence.AtLeastOnceDelivery.UnconfirmedWarning
import moe.taiho.course_selection.KryoSerializable
import moe.taiho.course_selection.Judge

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
    case class Query(student: Int, course: Int, content: String) extends Info
    case class Response(student: Int, course: Int, content: String) extends Info

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
	                judge.courseTable.addCourse(course)
                case Rejected(course, deliveryId, _) =>
                    assert(effective(course, deliveryId))
                    confirmDelivery(deliveryId)
                    selected remove course
                case Quitted(course, deliveryId) =>
                    assert(effective(course, deliveryId))
                    confirmDelivery(deliveryId)
                    selected.remove(course)
	                judge.courseTable.dropCourse(course)
                case Take(course) => deliver(courseRegion.path) {
                    deliveryId =>
                        selected get course foreach { t => if (!t._2) confirmDelivery(t._3) }
                        selected(course) = (true, false, deliveryId)
                        Course.Envelope(course, Course.Take(student = id, deliveryId))
                }
                case Quit(course) => deliver(courseRegion.path) {
                    deliveryId =>
                        selected get course foreach { t => if (!t._2) confirmDelivery(t._3) }
                        selected(course) = (false, false, deliveryId)
                        Course.Envelope(course, Course.Quit(student = id, deliveryId))
                }
            }
        }

        def query(course: Int): (Boolean, Boolean) = selected get course map { t => (t._1, t._2) } getOrElse (false, true)
    }

    var state = new State()
    val judge = new Judge(id)

    val sessions: mutable.Map[Int, ActorRef] = mutable.TreeMap()

    override def receiveRecover: Receive = {
        case m: Command => state.update(m)
    }

    override def receiveCommand: Receive = {
        case m @ Taken(course, deliveryId) =>
            if (state.effective(course, deliveryId)) persist(m) { m =>
                state.update(m)
                log.info(s"\033[32m ${id} take ${course}\033[0m")
                sessions get course foreach { s =>
                    s ! Success(student = id, course, target = true)
                    sessions remove course
                }
            }
        case m @ Rejected(course, deliveryId, reason) =>
            if (state.effective(course, deliveryId)) persist(m) { m =>
                state.update(m)
                sessions get course foreach { s =>
                    s ! Failure(student = id, course, target = false, reason)
                    sessions remove course
                }
            }
        case m @ Quitted(course, deliveryId) =>
            if (state.effective(course, deliveryId)) persist(m) { m =>
                state.update(m)
                log.info(s"\033[32m ${id} quit ${course}\033[0m")
                sessions get course foreach { s =>
                    s ! Success(student = id, course, target = false)
                    sessions remove course
                }
            }
        case m @ Take(course) =>
            // todo: do some check here!
            val ret = judge.registerCheck(course)
            if (ret.result) {
                state.query(course) match {
                    case (true, false) => // do nothing
                    case (true, true) => sender() ! Success(student = id, course, target = true)
                    case _ => persist(m) { m =>
                        sessions(course) = sender()
                        state.update(m)
                    }
                }
            } else {
                sender() ! Failure(student = id, course, target = true, ret.reason)
            }
        case m @ Quit(course) =>
            val ret = judge.dropCheck(course)
            if (ret.result) {
                state.query(course) match {
                    case (false, false) => // do nothing
                    case (false, true) => sender() ! Success(student = id, course, target = false)
                    case _ => persist(m) { m =>
                        sessions(course) = sender()
                        state.update(m)
                    }
                }
            } else {
                sender() ! Failure(student = id, course, target = false, ret.reason)
            }
        case m @ Table() =>
            val ret = judge.showTable()
            sender() ! Response(student = id, course = 0, content = ret)
        case DebugPrint(msg) => sender() ! (id + "Receive " + msg)
        case _: UnconfirmedWarning => // ignore
        case m => log.warning(s"\033[31munhandled message $m\033[0m")
    }

    override def persistenceId: String = s"Student-$id"
}

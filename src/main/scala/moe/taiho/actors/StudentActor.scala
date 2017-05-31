package moe.taiho.actors

import akka.actor.ActorRef
import akka.cluster.sharding.{ClusterSharding, ShardRegion}
import akka.event.Logging
import akka.persistence.{AtLeastOnceDelivery, PersistentActor}

import scala.collection.mutable

object StudentActor {
    sealed trait Command
    // Requested by frontend
    case class Take(course: Int) extends Command
    case class Drop(course: Int) extends Command
    // Course responses
    case class Taken(course: Int, deliveryId: Long) extends Command
    case class Full(course: Int, deliveryId: Long) extends Command
    case class Dropped(course: Int, deliveryId: Long) extends Command

    case class Envelope(id: Int, command: Command)

    // reply to frontend
    case class Success(student: Int, course: Int, target: Boolean)
    case class Failure(student: Int, course: Int, target: Boolean, reason: String)

    val ShardName = "Student"
    val extractEntityId: ShardRegion.ExtractEntityId = {
        case Envelope(id: Int, command: Command) => (id.toString, command)
    }
    def extractShardId(numShards: Int): ShardRegion.ExtractShardId = {
        case m: Envelope => (m.id.hashCode % numShards).toString
        case ShardRegion.StartEntity(id) => (id.toInt.hashCode % numShards).toString
    }
}

class StudentActor extends PersistentActor with AtLeastOnceDelivery {
    import StudentActor._

    val log = Logging(context.system, this)

    val id: Int = self.path.name.toInt

    val courseRegion = ClusterSharding(context.system).shardRegion(CourseActor.ShardName)

    class State {
        private val selected: mutable.Map[Int, (Boolean/*target*/, Boolean/*confirmed*/, Long)] = mutable.TreeMap()

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
                case Full(course, deliveryId) =>
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
                        CourseActor.Envelope(course, CourseActor.Take(id, deliveryId))
                }
                case Drop(course) => deliver(courseRegion.path) {
                    deliveryId =>
                        selected get course foreach { t => if (!t._2) confirmDelivery(t._3) }
                        selected(course) = (false, false, deliveryId)
                        CourseActor.Envelope(course, CourseActor.Drop(id, deliveryId))
                }
            }
        }

        def query(course: Int): (Boolean, Boolean) = selected get course map { t => (t._1, t._2) } getOrElse (false, true)
    }

    var state = new State()

    val requests: mutable.Map[Int, ActorRef] = mutable.TreeMap()

    override def receiveRecover: Receive = {
        case m: Command => state.update(m)
    }

    override def receiveCommand: Receive = {
        case m @ Taken(course, deliveryId) =>
            if (state.effective(course, deliveryId)) persist(m) { m =>
                state.update(m)
                requests get course foreach { s =>
                    s ! Success(id, course, true)
                    requests remove course
                }
            }
        case m @ Full(course, deliveryId) =>
            if (state.effective(course, deliveryId)) persist(m) { m =>
                state.update(m)
                requests get course foreach { s =>
                    s ! Success(id, course, true)
                    requests remove course
                }
            }
        case m @ Dropped(course, deliveryId) =>
            if (state.effective(course, deliveryId)) persist(m) { m =>
                state.update(m)
                requests get course foreach { s =>
                    s ! Success(id, course, false)
                    requests remove course
                }
            }
        case m @ Take(course) =>
            state.query(course) match {
                case (true, false) => // do nothing
                case (true, true) => sender() ! Success(id, course, true)
                case _ => persist(m) { m =>
                    requests(course) = sender()
                    state.update(m)
                }
            }
        case m @ Drop(course) =>
            state.query(course) match {
                case (false, false) => // do nothing
                case (false, true) => sender() ! Success(id, course, false)
                case _ => persist(m) { m =>
                    requests(course) = sender()
                    state.update(m)
                }
            }
    }

    override def persistenceId: String = s"Student-$id"
}

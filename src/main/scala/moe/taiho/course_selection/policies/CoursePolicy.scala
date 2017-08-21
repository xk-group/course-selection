package moe.taiho.course_selection.policies

import moe.taiho.course_selection.KryoSerializable
import moe.taiho.course_selection.actors.CommonMessage.Reason

object CoursePolicy {
    sealed trait Command extends KryoSerializable
    case class Limit(num: Int) extends Command

    case class CourseFull() extends Reason {
        override def message(): String = "No available seats"
    }
}

class CoursePolicy(id: Int) {
    import CoursePolicy._

    var numSelected: Int = 0
    var limit: Int = 0

    def validateTake(student: Int): Option[Reason] =
        if (numSelected < limit) None else Some(CourseFull())

    def take(student: Int): Unit = {
        numSelected += 1
    }

    def validateQuit(student: Int): Option[Reason] = None

    def drop(student: Int): Unit = {
        numSelected -= 1
    }

    def setPolicy(cmd: Command): Unit = cmd match {
        case Limit(num: Int) =>
            limit = num
    }

}

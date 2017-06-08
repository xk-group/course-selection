package moe.taiho.course_selection

import boopickle.DefaultBasic._
import moe.taiho.course_selection.actors.{Course, Student}
import moe.taiho.course_selection.actors.Course._
import moe.taiho.course_selection.actors.Student._

object BoopickleSerializable {
    implicit val serPickler = compositePickler[BoopickleSerializable]

    serPickler.join[Student.Command].join[Student.Envelope].join[Course.Command].join[Course.Envelope]
}

trait BoopickleSerializable {}

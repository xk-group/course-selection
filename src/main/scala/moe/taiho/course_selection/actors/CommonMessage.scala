package moe.taiho.course_selection.actors

import moe.taiho.course_selection.KryoSerializable

object CommonMessage {
    trait Reason extends KryoSerializable {
        def message(): String = this.toString
    }
}

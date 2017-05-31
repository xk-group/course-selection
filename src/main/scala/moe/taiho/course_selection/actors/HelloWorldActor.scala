package moe.taiho.course_selection.actors

import akka.actor.Actor
import akka.event.Logging

/**
  * Created by swordfeng on 17-5-29.
  */
class HelloWorldActor extends Actor {
    val log = Logging(context.system, this)

    def receive = {
        case "hello" => sender() ! "world!"
        case _ => log.info("unknown message")
    }
}

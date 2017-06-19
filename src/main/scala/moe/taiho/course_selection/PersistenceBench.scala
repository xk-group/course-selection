package moe.taiho.course_selection

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.persistence.PersistentActor

object PersistenceBench extends App {
    val system = ActorSystem("PersistenceBench")
    val driver = system.actorOf(Props[BenchDriver])
    driver ! Go()
}

case class Go() extends KryoSerializable
case class Run(count: Int, res: ActorRef) extends KryoSerializable
case class Finish() extends KryoSerializable

class BenchDriver extends Actor {
    var starttime: Long = 0
    var endtime: Long = 0
    var finished: Int = 0
    val concurrency: Int = 50
    val totalcount: Int = 100
    override def receive: Receive = {
        case _: Go =>
            val bench = context.actorOf(Props[BenchActor])
            starttime = System.nanoTime()
            for (i <- 0 until concurrency) {
                bench ! Run(totalcount, self)
            }
        case _: Finish =>
            finished += 1
            if (finished == 1) {
                endtime = System.nanoTime()
                println(s"Elapsed time: ${(endtime-starttime)/1000000} ms")
                context.system.terminate()
            }
    }
}

class BenchActor extends PersistentActor {
    override def receiveRecover: Receive = {
        case _ =>
    }
    
    override def receiveCommand: Receive = {
        case m@Run(count, res) =>
            persist(m) { m =>
                if (count == 0) {
                    res ! Finish()
                } else {
                    self ! Run(count - 1, res)
                }
            }
    }
    // Compare:
    /*
    override def receiveCommand: Receive = {
        case m@Run(count, res) => deferAsync(m) { m =>
            persistAsync(m) { m =>
                if (count == 0) {
                    res ! Finish()
                } else {
                    self ! Run(count - 1, res)
                }
            }
        }
    }
    */

    override def persistenceId: String = self.path.parent.name + "-" + self.path.name
}
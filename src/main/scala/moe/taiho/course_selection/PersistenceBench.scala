package moe.taiho.course_selection

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.persistence.{PersistentActor, RecoveryCompleted}

object PersistenceBench extends App {
    val system = ActorSystem("PersistenceBench")
    val driver = system.actorOf(Props[BenchDriver])
    driver ! Go()
}

case class Go() extends KryoSerializable
case class Run(count: Int, res: ActorRef) extends KryoSerializable
case class Finish() extends KryoSerializable
case class Ping() extends KryoSerializable

class BenchDriver extends Actor {
    var starttime: Long = 0
    var endtime: Long = 0
    var finished: Int = 0
    val concurrency: Int = 25000
    val totalcount: Int = 10
    override def receive: Receive = {
        case _: Go =>
            for (i <- 0 until concurrency) {
                val bench = context.actorOf(Props[BenchActor], i.toString)
                bench ! Ping()
            }
            starttime = System.nanoTime()
            for (i <- 0 until concurrency) {
                val bench = context.actorSelection(i.toString)
                bench ! Run(totalcount, self)
            }
        case _: Finish =>
            finished += 1
            if (finished == concurrency) {
                endtime = System.nanoTime()
                println(s"Elapsed time: ${(endtime-starttime)/1000000} ms")
                context.system.terminate()
            }
    }
}

class BenchActor extends PersistentActor {
    override def receiveRecover: Receive = {
        case RecoveryCompleted => //println("complete rec")
    }

    /*
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
    */
    // Compare:

    override def receiveCommand: Receive = {
        case m@Run(count, res) =>
            persist(m) { m =>
                if (count <= 1) {
                    res ! Finish()
                } else {
                    self ! Run(count - 1, res)
                }
            }
        case _: Ping =>
    }
    // and also try deferAsync

    override def persistenceId: String = self.path.parent.name + "-" + self.path.name
}
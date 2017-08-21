package moe.taiho.course_selection.cluster

import akka.actor.{ActorSystem, Props}
import System.nanoTime
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}

import moe.taiho.course_selection.actors.{Course, Student}

import scala.io.Source

object TesetMain extends App {
	class Commands(configFn : String) {
		var studentCommand : List[(Int, Student.Envelope)] = List()
		var courseCommand : List[(Int, Course.Envelope)] = List()
		val comPattern = "[a-zA-Z]+".r
		val numPattern = "[0-9]+".r
		for (line <- Source.fromResource(configFn).getLines) {
			(comPattern findFirstIn line) match {
				case Some("SetLimit") =>
					val (timeStamp :: courseID :: limitNum :: _) = numPattern.findAllMatchIn(line).toList.map(m => m.toString().toInt)
					println(s"TimeStamp ${timeStamp}")
					//courseCommand = courseCommand :+ (timeStamp, Course.Envelope(courseID, Course.SetLimit(limitNum)))
				case Some("Take") =>
					val (timeStamp :: studentID :: courseID :: _) = numPattern.findAllMatchIn(line).toList.map(m => m.toString().toInt)
					println(s"TimeStamp ${timeStamp}")
					studentCommand = studentCommand :+ (timeStamp, Student.Envelope(studentID, Student.Take(courseID)))
				case Some("Quit") =>
					val (timeStamp :: studentID :: courseID :: _) = numPattern.findAllMatchIn(line).toList.map(m => m.toString().toInt)
					println(s"TimeStamp ${timeStamp}")
					studentCommand = studentCommand :+ (timeStamp, Student.Envelope(studentID, Student.Quit(courseID)))
				case None => println("Error in line: $line")
			}
		}
	}

	implicit val system = ActorSystem("CourseSelectSystem")

	val studentRegion = ClusterSharding(system).startProxy(
		Student.ShardName, Student.Role,
		Student.extractEntityId, Student.extractShardId
	)

	val courseRegion = ClusterSharding(system).startProxy(
		Course.ShardName, Course.Role,
		Course.extractEntityId, Course.extractShardId
	)

	println("Go!")
	val commands = new Commands("test.conf")
	val studentList = commands.studentCommand
	val courseList = commands.courseCommand
	val beginTime : Double = nanoTime()
	val n = studentList.length
	val m = courseList.length
	var pn, pm = 0
	while (pn < n || pm < m) {
		if (pn < n) {
			val (stuStamp, stuCommand) = studentList(pn)
			if (stuStamp <= (nanoTime() - beginTime) / 1000) {
				studentRegion ! stuCommand
				pn += 1
			}
		}
		if (pm < m) {
			val (couStamp, couCommand) = courseList(pm)
			if (couStamp <= (nanoTime() - beginTime) / 1000) {
				courseRegion ! couCommand
				pm += 1
			}
		}
	}
}

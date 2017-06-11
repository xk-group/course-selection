package moe.taiho.course_selection

import akka.serialization.Serializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule

object JacksonSerializer {
    val mapper = new ObjectMapper()
    mapper.registerModule(DefaultScalaModule)
}

class JacksonSerializer extends Serializer {
    import JacksonSerializer._

    override def identifier: Int = 42 // must not be 0-40

    override def toBinary(o: AnyRef): Array[Byte] = mapper.writeValueAsBytes(o)

    override def includeManifest: Boolean = true

    override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = mapper.readValue(bytes, manifest.get).asInstanceOf[AnyRef]
}

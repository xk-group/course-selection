package moe.taiho.course_selection

import java.nio.ByteBuffer

import boopickle.Default._
import akka.serialization.Serializer
import moe.taiho.course_selection.BoopickleSerializable._

class BoopickleSerializer extends Serializer {
    override def identifier: Int = 44

    override def toBinary(o: AnyRef): Array[Byte] = Pickle.intoBytes(o.asInstanceOf[BoopickleSerializable]).array()

    override def includeManifest: Boolean = true

    override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = Unpickle[BoopickleSerializable].fromBytes(ByteBuffer.wrap(bytes))
}

/*
 * Copyright (c) 2021, United States Government, as represented by the
 * Administrator of the National Aeronautics and Space Administration.
 * All rights reserved.
 *
 * The RACE - Runtime for Airspace Concept Evaluation platform is licensed
 * under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package gov.nasa.race.core

import akka.actor.{ActorRef, ExtendedActorSystem}
import akka.serialization.{ByteBufferSerializer, Serialization, SerializationExtension, Serializer}
import gov.nasa.race.common.{OATHash, SettableByteBufferInputStream, SettableByteBufferOutputStream}
import gov.nasa.race.util.{SettableBAIStream, SettableDataInputStream, SettableDataOutputStream}

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import scala.collection.immutable.IntMap
import scala.collection.mutable
import scala.reflect.ClassTag


/**
  * we maintain our own registry since we need to detect collisions and want to be able
  * to lookup registered AkkaSerializers by means of stored identifiers
  *
  */
object AkkaSerializer {
  private var map = IntMap.empty[AkkaSerializer]

  def register (ser: AkkaSerializer): Unit = map = map + (ser.identifier, ser)
  def get (id: Int): Option[AkkaSerializer] = map.get(id)
  def contains (id: Int): Boolean = map.contains(id)

  protected def idFor (serializerCls: Class[_]): Int = {
    var key = serializerCls.getName
    var id = OATHash.hash(key)
    while (map.contains(id) || (id >= 0 && id <= 40)) { // [0..40] is reserved for Akka
      println(s"hash collision for serializer ${serializerCls.getName}, salting..")
      key += id
      id = OATHash.hash(key)
    }
    id
  }
}

/**
  * base class for an Akka serializer that only handles instances of a single type T.
  * This implementation favors minimizing allocation and 3rd party library dependencies
  *
  * Note that serializers are instantiated by Akka via reflection from two different ctors
  *  - parameterless
  *  - a single ExtendedActorSystem parameter
  * we assume the concrete class provides the second so that we can also serialize ActorRefs
  *
  * Note also that we do not include any payload type information in the serialized output, i.e. de-serializers have
  * to know a-priori about the input format. This seems justified since Akka serializer instances handle both serialization
  * and de-serialization in the same class, and we mostly assume single-type serializers. In cases where we have
  * to handle target type alternatives the manifest has to be used to discriminate between such alternatives
  */
abstract class AkkaSerializer (system: ExtendedActorSystem) extends Serializer with ByteBufferSerializer {

  val identifier: Int = AkkaSerializer.idFor(getClass)

  //--- for serialization
  val initCapacity: Int = 256 // override if we know the max size of objects to serialize
  protected val baos = new ByteArrayOutputStream(initCapacity) // only used for toBinary(o)
  protected val sbbos = new SettableByteBufferOutputStream() // only used for toBinary(o,byteBuf)
  protected val dos = new SettableDataOutputStream(baos)

  //--- for de-serialization
  protected val sbais = new SettableBAIStream()  // only used for fromBinary(bytes)
  protected val sbbis = new SettableByteBufferInputStream() // only used for fromBinary(byteBuffer)
  protected val dis = new SettableDataInputStream(sbais)

  AkkaSerializer.register(this)  // only for our internal lookup


  //--- serialization

  def includeManifest: Boolean = false // override if we need a manifest (type hint) for the deserializer

  def findSerializerFor (o: AnyRef): Serializer = {
    val see = SerializationExtension(system)
    see.findSerializerFor(o)
  }

  protected def _serialize (o: AnyRef): Unit // to be provided by concrete type

  override def toBinary(o: AnyRef): Array[Byte] = {
    baos.reset()
    dos.setOutputStream(baos)
    _serialize(o)
    baos.toByteArray // this is copying
  }

  override def toBinary (o: AnyRef, bb: ByteBuffer): Unit = {
    sbbos.setByteBuffer(bb)
    dos.setOutputStream(sbbos)
    _serialize(o)
  }

  // this is our own zero-copy method for embedded serialization
  def toEmbeddedBinary (o: AnyRef, embeddingSerializer: AkkaSerializer): Unit = {
    dos.setOutputStream(embeddingSerializer.dos.getOutputStream)
    _serialize(o)
  }

  def writeEmbedded (v: Any): Unit = {
    v match {
      case o: AnyRef =>
        findSerializerFor(o) match {  // this will throw if there is none configured
          case aser: AkkaSerializer =>
            writeInt(aser.identifier)
            aser.toEmbeddedBinary(o, this)

          case ser: Serializer => // generic case - not a RACE serializer
            writeInt(ser.identifier)
            writeBytes(ser.toBinary(o))
        }
      case _ => // TODO - we should handle AnyVals here
    }

  }

  //--- deserialization

  protected def _deserialize(manifest: Option[_]): AnyRef // to be provided by concrete type

  override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    sbais.setBuffer(bytes)
    dis.setInputStream(sbais)
    _deserialize(manifest)
  }

  override def fromBinary(bb: ByteBuffer, manifest: String): AnyRef = {
    sbbis.setByteBuffer(bb)
    dis.setInputStream(sbbis)
    _deserialize(Option(manifest))
  }

  def fromEmbeddedBinary (manifest: Option[_], embeddingSerializer: AkkaSerializer): AnyRef = {
    dis.setInputStream(embeddingSerializer.dis.getInputStream)
    _deserialize(manifest)
  }

  // for use of embedded objects via Serialization.deserializeByteBuffer(..)
  protected def remainingByteBuffer: ByteBuffer = {
    dis.getInputStream match {
      case `sbais` => sbais.getRemainingByteBuffer()
      case `sbbis` => sbbis.getByteBuffer
      case _ => throw new RuntimeException("unknown input stream")
    }
  }

  // note the manifest is for the outer type, which has to include the type hints
  protected def readEmbedded (): Any = {
    val see = SerializationExtension(system)
    val serializerId = readInt()

    AkkaSerializer.get(serializerId) match {  // Akka doesn't let us query serializers by id
      case Some(aser) => aser.fromEmbeddedBinary(None, this)
      case _ => see.deserializeByteBuffer(remainingByteBuffer,serializerId,null)  // generic case
    }
  }

  //--- inlined convenience methods to be used from serialize() / deserialize() methods

  @inline final def writeUTF(v: String): Unit = dos.writeUTF(v)
  @inline final def writeBoolean(v: Boolean): Unit = dos.writeBoolean(v)
  @inline final def writeByte(v: Byte): Unit = dos.writeByte(v)
  @inline final def writeShort(v: Short): Unit = dos.writeShort(v)
  @inline final def writeInt(v: Int): Unit = dos.writeInt(v)
  @inline final def writeLong(v:Long): Unit = dos.writeLong(v)
  @inline final def writeFloat(v: Float): Unit = dos.writeFloat(v)
  @inline final def writeDouble(v:Double): Unit = dos.writeDouble(v)
  @inline final def writeActorRef (aRef: ActorRef): Unit = dos.writeUTF(Serialization.serializedActorPath(aRef))

  @inline final def writeBytes (vs: Array[Byte]): Unit = dos.write(vs)
  @inline final def writeBytes (vs: Array[Byte], off: Int, len: Int): Unit = dos.write(vs,off,len)

  @inline final def readUTF(): String = dis.readUTF()
  @inline final def readBoolean(): Boolean = dis.readBoolean()
  @inline final def readByte(): Byte = dis.readByte()
  @inline final def readInt(): Int = dis.readInt()
  @inline final def readLong(): Long = dis.readLong()
  @inline final def readFloat(): Float = dis.readFloat()
  @inline final def readDouble(): Double = dis.readDouble()
  @inline final def readActorRef(): ActorRef = system.provider.resolveActorRef(dis.readUTF())

  //--- serializing/de-serializing collections

  def writeItems[T] (items: Iterable[T]) (writeItem: T=>Unit): Unit = {
    dos.writeInt(items.size)
    items.foreach(writeItem)
  }

  def readItems[T](readItem: ()=>T): Seq[T] = {
    var n = dis.readInt()
    if (n < 0 || n > 10000) throw new RuntimeException(s"deserialization size constraints violated: $n")
    val items = new mutable.ArrayBuffer[T](n)
    while (n > 0) {
      items += readItem()
    }
    items.toSeq
  }

  def writeMap[K,V] (map: Map[K,V]) (writeEntry: ((K,V))=>Unit): Unit = {
    dos.writeInt(map.size)
    map.foreach(writeEntry)
  }

  def readMap[K,V](readEntry: ()=>(K,V)): Map[K,V] = {
    var n = dis.readInt()
    if (n < 0 || n > 10000) throw new RuntimeException(s"deserialization size constraints violated: $n")
    val map = mutable.Map[K,V]()
    while (n > 0) {
      map += readEntry()
    }
    map.toMap
  }
}

/**
  * base class for an Akka serializer that only handles instances of a single type T
  */
abstract class SingleTypeAkkaSerializer [T <: AnyRef :ClassTag] (system: ExtendedActorSystem) extends AkkaSerializer(system) {
  protected var manifest: Option[_] = None

  protected def _serialize (o: AnyRef): Unit = serialize(o.asInstanceOf[T])
  protected def _deserialize(man: Option[_]): AnyRef = {
    manifest = man
    deserialize(): T
  }

  //--- the serialization/deserialization methods to be provided by the concrete type
  def serialize(t: T): Unit
  def deserialize(): T
}
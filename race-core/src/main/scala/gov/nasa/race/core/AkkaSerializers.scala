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
import gov.nasa.race.common.{OATHash, SettableByteBufferInputStream, SettableByteBufferOutputStream, foreachRef}
import gov.nasa.race.geo.{GeoPosition, XYPos}
import gov.nasa.race.uom.Angle.Degrees
import gov.nasa.race.uom.Length.Meters
import gov.nasa.race.uom.Speed.MetersPerSecond
import gov.nasa.race.uom.{Angle, DateTime, Length, Speed}
import gov.nasa.race.util.{SettableBAIStream, SettableDataInputStream, SettableDataOutputStream}

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import scala.collection.immutable.IntMap
import scala.collection.{immutable, mutable}
import scala.reflect.{ClassTag, classTag}


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

  //--- our own iterable serializers
  // set during registration from within the respective serializer
  protected[core] var refArraySerializer: RefArraySerializer = null
  protected[core] var refSeqSerializer: RefSeqSerializer = null
  protected[core] var stringArraySerializer: StringArraySerializer = null
  protected[core] var stringSeqSerializer: StringSeqSerializer = null


  protected[core] var intArraySerializer: IntArraySerializer = null
  protected[core] var intSeqSerializer: IntSeqSerializer = null
  protected[core] var longArraySerializer: LongArraySerializer = null
  protected[core] var longSeqSerializer: LongSeqSerializer = null
  protected[core] var doubleArraySerializer: DoubleArraySerializer = null
  protected[core] var doubleSeqSerializer: DoubleSeqSerializer = null
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
  *
  * TODO - check if mixing in ByteBufferSerializer in a synchronized context is not tripping Akka
  * if the ByteBuffer is from a pool and there are system locks held while the user serializer processes it
  */
abstract class AkkaSerializer (system: ExtendedActorSystem) extends Serializer with ByteBufferSerializer {

  val identifier: Int = AkkaSerializer.idFor(getClass)

  //--- for serialization
  val initCapacity: Int = 4096 // override if we know the max size of objects to serialize
  protected val baos = new ByteArrayOutputStream(initCapacity) // only used for toBinary(o)
  protected val sbbos = new SettableByteBufferOutputStream() // only used for toBinary(o,byteBuf)
  protected val dos = new SettableDataOutputStream(baos)
  protected val writeLock = new Object()

  //--- for de-serialization
  protected val sbais = new SettableBAIStream()  // only used for fromBinary(bytes)
  protected val sbbis = new SettableByteBufferInputStream() // only used for fromBinary(byteBuffer)
  protected val dis = new SettableDataInputStream(sbais)
  protected val readLock = new Object()

  AkkaSerializer.register(this)  // only for our internal lookup

  //--- serialization

  def includeManifest: Boolean = false // override if we need a manifest (type hint) for the deserializer

  def findSerializerFor (o: AnyRef): Serializer = {
    val see = SerializationExtension(system)
    see.findSerializerFor(o)
  }

  protected def _serialize (o: AnyRef): Unit // to be provided by concrete type

  override def toBinary(o: AnyRef): Array[Byte] = writeLock.synchronized {
    baos.reset()
    dos.setOutputStream(baos)
    _serialize(o)
    baos.toByteArray // this is copying
  }

  override def toBinary (o: AnyRef, bb: ByteBuffer): Unit = writeLock.synchronized {
    sbbos.setByteBuffer(bb)
    dos.setOutputStream(sbbos)
    _serialize(o)
  }

  // this is our own zero-copy method for embedded serialization
  def toEmbeddedBinary (o: AnyRef, embeddingSerializer: AkkaSerializer): Unit = {
    dos.setOutputStream(embeddingSerializer.dos.getOutputStream)
    _serialize(o)
  }

  def writeEmbeddedRef(v: AnyRef): Unit = {
    findSerializerFor(v) match {  // this will throw if there is none configured
      case aser: AkkaSerializer =>
        writeInt(aser.identifier)
        aser.toEmbeddedBinary(v, this)

      case ser: Serializer => // generic case - not a RACE serializer
        writeInt(ser.identifier)
        writeBytes(ser.toBinary(v))
    }
  }

  def writeEmbedded(v: Any): Unit = {
    v match {
      case o: AnyRef => writeEmbeddedRef(o)
      case other => println("don't know how to serialize " + other.getClass)
    }
  }


  def writeEmbeddedRefArray[T <:AnyRef] (vs: Iterable[T]): Unit = writeEmbeddedWithSerializer(AkkaSerializer.refArraySerializer, vs)
  def writeEmbeddedRefSeq[T <:AnyRef] (vs: Iterable[T]): Unit = writeEmbeddedWithSerializer(AkkaSerializer.refSeqSerializer, vs)
  def writeEmbeddedStringArray (vs: Iterable[String]): Unit = writeEmbeddedWithSerializer(AkkaSerializer.stringArraySerializer, vs)
  def writeEmbeddedStringSeq (vs: Iterable[String]): Unit = writeEmbeddedWithSerializer(AkkaSerializer.stringSeqSerializer, vs)
  def writeEmbeddedIntArray (vs: Iterable[Int]): Unit = writeEmbeddedWithSerializer(AkkaSerializer.intArraySerializer, vs)
  def writeEmbeddedIntSeq (vs: Iterable[Int]): Unit = writeEmbeddedWithSerializer(AkkaSerializer.intSeqSerializer, vs)
  def writeEmbeddedLongArray (vs: Iterable[Long]): Unit = writeEmbeddedWithSerializer(AkkaSerializer.longArraySerializer, vs)
  def writeEmbeddedLongSeq (vs: Iterable[Long]): Unit = writeEmbeddedWithSerializer(AkkaSerializer.longSeqSerializer, vs)
  def writeEmbeddedDoubleArray (vs: Iterable[Double]): Unit = writeEmbeddedWithSerializer(AkkaSerializer.doubleArraySerializer, vs)
  def writeEmbeddedDoubleSeq (vs: Iterable[Double]): Unit = writeEmbeddedWithSerializer(AkkaSerializer.doubleSeqSerializer, vs)

  protected def writeEmbeddedWithSerializer(ser: AkkaSerializer, v: AnyRef): Unit = {
    writeInt(ser.identifier)
    ser.toEmbeddedBinary(v,this)
  }

  //--- deserialization

  protected def _deserialize(manifest: Option[_]): AnyRef // to be provided by concrete type

  override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = readLock.synchronized {
    sbais.setBuffer(bytes)
    dis.setInputStream(sbais)
    _deserialize(manifest)
  }

  override def fromBinary(bb: ByteBuffer, manifest: String): AnyRef = readLock.synchronized {
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
      case sbais: SettableBAIStream => sbais.getRemainingByteBuffer()
      case sbbis: SettableByteBufferInputStream => sbbis.getByteBuffer
      case other => throw new RuntimeException(s"unknown input stream $other")
    }
  }

  // note the manifest is for the outer type, which has to include the type hints
  protected def readEmbeddedRef(): AnyRef = {
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

  @inline final def writeBytes (vs: Array[Byte]): Unit = dos.write(vs)
  @inline final def writeBytes (vs: Array[Byte], off: Int, len: Int): Unit = dos.write(vs,off,len)

  @inline final def writeActorRef (aRef: ActorRef): Unit = dos.writeUTF(Serialization.serializedActorPath(aRef))
  @inline final def writeIsDefined (o: Option[_]): Boolean = {
    dos.writeBoolean(o.isDefined)
    o.isDefined
  }

  //--- common RACE types
  @inline final def writeDateTime (d: DateTime): Unit = dos.writeLong(d.toEpochMillis)
  @inline final def writeGeoPosition (pos: GeoPosition): Unit = {
    writeDouble(pos.φ.toDegrees)
    writeDouble(pos.λ.toDegrees)
    writeDouble(pos.altitude.toMeters)
  }
  @inline final def writeXYPos (pos: XYPos): Unit = {
    writeDouble(pos.x.toMeters)
    writeDouble(pos.y.toMeters)
  }
  @inline final def writeSpeed (v: Speed): Unit = dos.writeDouble(v.toMetersPerSecond)
  @inline final def writeAngle (a: Angle): Unit = dos.writeDouble(a.toDegrees)
  @inline final def writeLength (l: Length): Unit = dos.writeDouble(l.toMeters)


  @inline final def readUTF(): String = dis.readUTF()
  @inline final def readBoolean(): Boolean = dis.readBoolean()
  @inline final def readByte(): Byte = dis.readByte()
  @inline final def readInt(): Int = dis.readInt()
  @inline final def readLong(): Long = dis.readLong()
  @inline final def readFloat(): Float = dis.readFloat()
  @inline final def readDouble(): Double = dis.readDouble()
  @inline final def readActorRef(): ActorRef = system.provider.resolveActorRef(dis.readUTF())
  @inline final def readIsDefined(): Boolean = dis.readBoolean()

  //--- common RACE types
  @inline final def readDateTime(): DateTime = DateTime.ofEpochMillis(dis.readLong())
  @inline final def readGeoPosition(): GeoPosition = {
    val lat = readDouble()
    val lon = readDouble()
    val alt = readDouble()
    GeoPosition.fromDegreesAndMeters(lat,lon,alt)
  }
  @inline final def readXYPos(): XYPos = {
    val x = Meters(readDouble())
    val y = Meters(readDouble())
    XYPos(x,y)
  }
  @inline final def readSpeed(): Speed = MetersPerSecond(readDouble())
  @inline final def readAngle(): Angle = Degrees(readDouble())
  @inline final def readLength(): Length = Meters(readDouble())

  //--- serializing/de-serializing collections

  def writeItems[T] (items: Iterable[T]) (writeItem: T=>Unit): Unit = {
    dos.writeInt(items.size)
    items.foreach(writeItem)
  }

  def readItems[T:ClassTag](readItem: ()=>T): Array[T] = {
    var n = dis.readInt()

    if (n < 0 || n > 10000) throw new RuntimeException(s"deserialization size constraints violated: $n")
    val items = new Array[T](n)
    var i = 0
    while (i < n) {
      items(i) = readItem()
      i += 1
    }
    items
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

  protected def _serialize (o: AnyRef): Unit = {
    //println(s"\n@@@ serialize $o with serializer for ${classTag[T].runtimeClass}")
    serialize(o.asInstanceOf[T])
  }
  protected def _deserialize(man: Option[_]): AnyRef = {
    //println(s"\n@@@ deserialize with serializer for ${classTag[T].runtimeClass}")
    manifest = man
    deserialize(): T
  }

  //--- the serialization/deserialization methods to be provided by the concrete type
  def serialize(t: T): Unit
  def deserialize(): T
}

//--- collections we handle (as embedded objects)

abstract class IterableSerializer[T] (system: ExtendedActorSystem) extends AkkaSerializer(system) {
  protected def toCollectionType (a: Array[T]): AnyRef
}

trait ArraySerializer[T] {
  protected def toCollectionType (a: Array[T]): AnyRef = a
}

trait SeqSerializer[T] {
  protected def toCollectionType (a: Array[T]): AnyRef = a.toSeq
}


//--- reference type collection serializers

/**
  * base for generic Iterables that have homogeneous elements (all elements use the same serializer)
  *
  * note that these are only used embedded, i.e. the outer serializer has to make sure the target type is correct
  * note also that we have a specialized serializer for String element iterables
  */
abstract class RefIterableSerializer(system: ExtendedActorSystem) extends IterableSerializer[AnyRef](system) {

  protected def _serialize (o: AnyRef): Unit = {
    val it = o.asInstanceOf[Iterable[_]]

    writeInt(it.size)  // number of serialized Seq elements

    if (it.nonEmpty) {
      val e0 = it.head
      findSerializerFor(e0.asInstanceOf[AnyRef]) match {
        case aser: AkkaSerializer =>
          writeInt(aser.identifier)  // id of element serializer used
          foreachRef(it)( e=> aser.toEmbeddedBinary(e,this))

        case ser: Serializer => // generic case - not a RACE serializer
          writeInt(ser.identifier)
          foreachRef(it)( e=> writeBytes(ser.toBinary(e)))
      }
    }
  }

  protected def _deserialize (man: Option[_]): AnyRef = {
    val size = readInt() // number of serialized Seq elements
    if (size > 0) {
      val buf = new Array[AnyRef](size)
      val see = SerializationExtension(system)
      val serializerId = readInt() // id of element serializer

      AkkaSerializer.get(serializerId) match {  // Akka doesn't let us query serializers by id
        case Some(aser) =>
          for (i <- 0 to size) { buf(i) = aser.fromEmbeddedBinary(None, this) }

        case _ => // not an Akka serializer
          var bb = remainingByteBuffer
          for (i <- 0 to size) {
            buf(i) = see.deserializeByteBuffer(bb,serializerId,null)
            //bb = bb.slice(bb.position(), bb.limit() - bb.position()) // TODO - do we need this? Only if Akka resets the buffer
          }
      }

      toCollectionType(buf)

    } else toCollectionType(Array.empty[AnyRef])
  }
}

class RefArraySerializer (system: ExtendedActorSystem) extends RefIterableSerializer(system) with ArraySerializer[AnyRef] {
  AkkaSerializer.refArraySerializer = this
}

class RefSeqSerializer (system: ExtendedActorSystem) extends RefIterableSerializer(system) with SeqSerializer[AnyRef] {
  AkkaSerializer.refSeqSerializer = this
}

//--- builtin element type collection serializers

//--- Int

abstract class IntIterableSerializer (system: ExtendedActorSystem) extends IterableSerializer[Int](system) {

  protected def _serialize (o: AnyRef): Unit = {
    val it = o.asInstanceOf[Iterable[Int]]
    writeInt(it.size) // number of serialized Seq elements
    it.foreach(writeInt)
  }

  protected def _deserialize (man: Option[_]): AnyRef = {
    val size = readInt() // number of serialized elements
    if (size > 0) {
      val buf = new Array[Int](size)
      for (i <- 0 to size) { buf(i) = readInt() }
      toCollectionType(buf)
    } else toCollectionType(Array.empty[Int])
  }
}

class IntArraySerializer (system: ExtendedActorSystem) extends IntIterableSerializer(system) with ArraySerializer[Int] {
  AkkaSerializer.intArraySerializer = this
}
class IntSeqSerializer (system: ExtendedActorSystem) extends IntIterableSerializer(system) with SeqSerializer[Int] {
  AkkaSerializer.intSeqSerializer = this
}

//--- Long

abstract class LongIterableSerializer (system: ExtendedActorSystem) extends IterableSerializer[Long](system) {

  protected def _serialize (o: AnyRef): Unit = {
    val it = o.asInstanceOf[Iterable[Long]]
    writeInt(it.size) // number of serialized Seq elements
    it.foreach(writeLong)
  }

  protected def _deserialize (man: Option[_]): AnyRef = {
    val size = readInt() // number of serialized elements
    if (size > 0) {
      val buf = new Array[Long](size)
      for (i <- 0 to size) { buf(i) = readLong() }
      toCollectionType(buf)
    } else toCollectionType(Array.empty[Long])
  }
}

class LongArraySerializer (system: ExtendedActorSystem) extends LongIterableSerializer(system) with ArraySerializer[Long] {
  AkkaSerializer.longArraySerializer = this
}
class LongSeqSerializer (system: ExtendedActorSystem) extends LongIterableSerializer(system) with SeqSerializer[Long] {
  AkkaSerializer.longSeqSerializer = this
}

//--- Double

abstract class DoubleIterableSerializer (system: ExtendedActorSystem) extends IterableSerializer[Double](system) {

  protected def _serialize (o: AnyRef): Unit = {
    val it = o.asInstanceOf[Iterable[Double]]
    writeInt(it.size) // number of serialized Seq elements
    it.foreach(writeDouble)
  }

  protected def _deserialize (man: Option[_]): AnyRef = {
    val size = readInt() // number of serialized elements
    if (size > 0) {
      val buf = new Array[Double](size)
      for (i <- 0 to size) { buf(i) = readDouble() }
      toCollectionType(buf)
    } else toCollectionType(Array.empty[Double])
  }
}


class DoubleArraySerializer (system: ExtendedActorSystem) extends DoubleIterableSerializer(system) with ArraySerializer[Double] {
  AkkaSerializer.doubleArraySerializer = this
}
class DoubleSeqSerializer (system: ExtendedActorSystem) extends DoubleIterableSerializer(system) with SeqSerializer[Double] {
  AkkaSerializer.doubleSeqSerializer = this
}
class SeqDouble // we just need something for the registration

//--- String

abstract class StringIterableSerializer (system: ExtendedActorSystem) extends IterableSerializer[String](system) {

  protected def _serialize (o: AnyRef): Unit = {
    val it = o.asInstanceOf[Iterable[String]]
    writeInt(it.size) // number of serialized Seq elements
    it.foreach(writeUTF)
  }

  protected def _deserialize (man: Option[_]): AnyRef = {
    val size = readInt() // number of serialized elements
    if (size > 0) {
      val buf = new Array[String](size)
      for (i <- 0 to size) { buf(i) = readUTF() }
      toCollectionType(buf)
    } else toCollectionType(Array.empty[String])
  }
}

class StringArraySerializer (system: ExtendedActorSystem) extends StringIterableSerializer(system) with ArraySerializer[String]
class StringSeqSerializer (system: ExtendedActorSystem) extends StringIterableSerializer(system) with SeqSerializer[String]
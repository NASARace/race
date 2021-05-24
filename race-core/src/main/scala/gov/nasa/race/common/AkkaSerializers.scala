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
package gov.nasa.race.common

import akka.actor.{ActorRef, ExtendedActorSystem}
import akka.serialization.{Serialization, Serializer}
import gov.nasa.race.util.SettableBAIStream

import java.io.{ByteArrayOutputStream, DataInputStream, DataOutputStream}
import scala.reflect.{ClassTag, classTag}


/**
  * base class for an Akka serializer that only handles instances of a single type T
  * this implementation favors minimizing allocation and 3rd party library dependencies
  *
  * Note that serializers are instantiated by Akka via reflection from two different ctors
  *  - parameterless
  *  - a single ExtendedActorSystem parameter
  * we assume the concrete class provides the second so that we can also serialize ActorRefs
  */
abstract class AkkaSerializer (system: ExtendedActorSystem) extends Serializer {
  val initCapacity: Int = 256 // override if we know the max size of objects to serialize

  // for serialization
  protected val baos = new ByteArrayOutputStream(initCapacity)
  protected val dos = new DataOutputStream(baos)

  // for de-serialization
  protected val sbais = new SettableBAIStream()
  protected val dis = new DataInputStream(sbais)

  override def identifier: Int = {
    OATHash.hash(getClass.getName) // FIXME - this should block out 0-40, which is for Akka
  }

  //--- for subtypes that have to implement their own fromBinary/toBinary
  @inline def clear(): Unit = baos.reset()
  @inline def toByteArray: Array[Byte] = baos.toByteArray
  @inline def setData (bytes: Array[Byte]): Unit = sbais.setBuffer(bytes)

  @inline def empty = Array.empty[Byte]

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

  @inline final def readUTF(): String = dis.readUTF()
  @inline final def readBoolean(): Boolean = dis.readBoolean()
  @inline final def readByte(): Byte = dis.readByte()
  @inline final def readInt(): Int = dis.readInt()
  @inline final def readLong(): Long = dis.readLong()
  @inline final def readFloat(): Float = dis.readFloat()
  @inline final def readDouble(): Double = dis.readDouble()
  @inline final def readActorRef(): ActorRef = system.provider.resolveActorRef(dis.readUTF())
}

abstract class MultiTypeAkkaSerializer (system: ExtendedActorSystem) extends AkkaSerializer(system) {

  //--- the concrete serializers that can handle multiple types
  def serialize (o:AnyRef): Unit
  def deserialize (manifest: Option[Class[_]]): AnyRef

  override def toBinary(o: AnyRef): Array[Byte] = dos.synchronized {
    baos.reset()
    serialize(o)
    baos.toByteArray
  }

  override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = sbais.synchronized {
    sbais.setBuffer(bytes)
    deserialize(manifest)
  }
}

/**
  * base class for an Akka serializer that only handles instances of a single type T and therefore does not need a manifest
  */
abstract class SingleTypeAkkaSerializer [T <: AnyRef :ClassTag] (system: ExtendedActorSystem) extends AkkaSerializer(system) {

  //--- the concrete serialization/deserialization methods handling a single type
  def serialize(t: T): Unit
  def deserialize(): T

  override def includeManifest: Boolean = false // no need for manifest we only handle T instances

  override def toBinary(o: AnyRef): Array[Byte] = dos.synchronized {
    baos.reset()
    o match {
      case t: T =>
        baos.reset()
        serialize(t)
        baos.toByteArray

      case other => throw new IllegalArgumentException(s"${getClass.getName} cannot handle objects of type: ${other.getClass.getName}")
    }
  }

  override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = sbais.synchronized {
    sbais.setBuffer(bytes)
    deserialize()
  }
}
Remote RaceActors and Distributed RACE Applications
===================================================
RACE is meant to be used in a distributed environment, communicating with other nodes across network connections. This
communication can happen at three levels:

* transparently through `Akka Clusters`_
* transparently between RaceActors in different processes
* at the application level with dedicated actors that directly encode protocols/formats

Akka Clusters
-------------

Remote RaceActors
-----------------

Dedicated Communication Actors
------------------------------

Location Transparency
---------------------

.. image:: images/loc-trans.svg
    :class: center scale50
    :alt: Location Transparent Actors

Serialization
-------------
Regardless of the level, communication between RaceActors in different processes always involves serialization and
de-serialization of exchanged messages. While older RACE versions used generic `Java Serialization`_ that did not
require to identify which message types need to be serialized/deserialized this is now discouraged by Akka for
security reasons (see `Java Security Guidelines`_ and `Perils of Java Deserialization`_).

For clusters and remote RaceActors RACE now defaults to a simple ``java.io.DataOutputStream`` and ``DataInputStream``
based approach that requires RACE configuration in order to whitelist serializable types and associate them with
respective ``gov.nasa.race.core.AkkaSerializers`` that provide implementations for two basic methods::

    def toBinary(o: AnyRef): Array[Byte]
    def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef

Serializers for standard RACE system messages can be found in ``gov.nasa.race.core.RaceSerializers`` and are generally
type specific, i.e. they only handle a single type which is uniquely identified in the receiving RACE system
by means of its Akka serializer id (that is transparently sent by Akka).

RACE channel messages require additional effort since they are instances of a generic::

    case class BusEvent (channel: String, msg: Any, sender: ActorRef)

that does not explicilty specify the ``msg`` payload type. In order to keep respective actors location transparent the
serialization binding is delegated to the respective payload object, such as::

    class FlightPos (val id: String, val position: GeoPosition, ...)  .. with AkkaSerializable {
      def toMessage(channel: Channel, sender: ActorRef) = SerializableFlightPosMessage(channel, this, sender)
      ...

The ``gov.nasa.race.core.AkkaSerializable`` defines a single ``toMessage(..): SerializableMessage`` method which is
used by RACE to convert the payload object (``FlightPos``) to a class for which Akka has a configured serializer
(``SerializableFlightPosMessage``)::

    case class SerializableFlightPosMessage (channel: Channel, msg: FlightPos, sender: ActorRef) extends SerializableMessage {
      def toBusEvent: BusEvent = BusEvent(channel,msg,sender)
    }

This conversion happens transparently to the sending RaceActor during its ``publish(fpos)`` call, which delegates to the
underlying::

    case class RemoteBusInterface (val masterRef: ActorRef, val connectorRef: ActorRef) extends BusInterface {
      def publish (channel: Channel, msg: Any, sender: ActorRef): Unit = {
        msg match {
          case as: AkkaSerializable => connectorRef ! as.toMessage(channel,sender)
        ...

At this point Akka takes over and looks up the configured serializer for the concrete ``SerializableMessage`` type::

    // serialization bindings for race-air (bus) messages
    akka {
      actor {
        serializers {
          fposSerializer = "gov.nasa.race.air.FlightPosSerializer"
        }
        serialization-bindings {
          "gov.nasa.race.air.SerializableFlightPosMessage" = fposSerializer
        }
      }
    }



This type implements a ``toBusEvent`` method that turns the deserialized object in the receiving RACE system back into
a BusEvent.

    class FlightPosSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[SerializableFlightPosMessage](system) {
      override def serialize (e: SerializableFlightPosMessage): Unit = {
        val fpos = e.msg
        writeUTF(e.channel)
        writeActorRef(e.sender)
        //--- payload
        writeUTF(fpos.id)
        writeDouble(fpos.position.φ.toDegrees)
        ..
      }

      override def deserialize (): SerializableFlightPosMessage = {
        val channel = readUTF()
        val senderRef = readActorRef()
        //--- payload
        val id  = readUTF()
        val lat = readDouble()
        ...
        SerializableFlightPosMessage(channel, new FlightPos(id, GeoPosition.fromDegreesAndMeters(lat,..),..), senderRef)
      }




:: _Akka Clusters: https://doc.akka.io/docs/akka/current/typed/index-cluster.html
:: _Akka Remoting: https://doc.akka.io/docs/akka/current/remoting-artery.html
:: _Akka Serialization: https://doc.akka.io/docs/akka/current/serialization.html
:: _Java Serialization: https://docs.oracle.com/en/java/javase/16/docs/specs/serialization/
:: _Java Security Guidelines: https://www.oracle.com/java/technologies/javase/seccodeguide.html
:: _Perils of Java Deserialization: https://community.microfocus.com/cyberres/fortify/f/fortify-discussions/317555/the-perils-of-java-deserialization
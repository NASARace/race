Remote RaceActors and Distributed RACE Applications
===================================================
RACE is meant to be used in a distributed environment, communicating with other nodes across network connections. This
communication can happen at three levels:

* transparently through `Akka Clusters`_
* transparently between RaceActors in different processes
* at the application level with dedicated actors that directly encode protocols/formats

Tightly related to the first two modes is the concept of **Location Transparency**. In theory this means the code of
an actor should not reflect in which actor system it is running - the location can be chosen based on resource
availability, including load distribution. In practice, this is constrained by the cost of serialization for incoming
and outgoing messages for respective actors, which for high volume communication can exceed potential gains.


Akka Clusters
-------------
This is the lowest level of remoting support and - apart from serialization of exchanged messages - only requires
`cluster configuration`_ as it is fully implemented in the Akka framework. Clusters can be used to implement highly
reliable systems with `distributed data`_ but due to underlying protocols require a high level of connectivity between
the nodes within the cluster. There is not yet specific support for Akka clusters within RACE.

Remote RaceActors
-----------------
This is the primary remoting support within RACE, which is integrated into the `RaceActor Model`_. If the ``MasterActor``
during system initialization encounters an actor configuration with a ``remote`` option it does not instantiate this
actor locally but - depending on if the there is a ``class`` specification for this actor - tries to either start
or lookup this actor in a remote RaceActorSystem::

1. **remote lookup** - if there is no ``class`` specification the ``MasterActor`` assumes the remote actor is already
   running within the remote system, tries to obtain its ``ActorRef`` and if successful sends its local actor
   configuration
2. **remote start** - if there is a ``class`` specification in the local config the ``MasterActor`` instantiates the
   respective actor in the remote system, which already needs to be running. Note this mode is only supported
   in `Akka Classic`_ (which is still the basis for RACE) and therefore might be dropped in the future

*Location Transparency* is guaranteed in both cases. Sending messages to remote actors is handled transparently by
Akka. Publishing to global RACE channels from remote actors requires a local ``BusConnector`` which is created
automatically by the ``MasterActor`` and acts as a local proxy that manages channel subscription and publication in lieu
of the remote. Location transparency therefore becomes just a matter of RACE configuration:

.. image:: ../images/loc-trans.svg
    :class: center scale60
    :alt: Location Transparent Actors

Dedicated Communication Actors
------------------------------
This mode does not strive for location transparency - it uses dedicated communication actors that have full control
over network protocols and endpoint lookup. SHARE_ is an example of choosing specialized actors (``UpstreamConnectorActor``
talking to a ``HttpServer``/``NodeServerRoute``) in order to enable connectivity-based actor modes (e.g. implementing
mode specific protocols) and to allow for alternative (non-RACE) implementations of end points.


Serialization
-------------
Regardless of the remoting level, communication between RaceActors in different processes always involves serialization
and de-serialization of exchanged messages. While older RACE versions used generic `Java Serialization`_ that did not
require to identify which message types need to be serialized/deserialized this is now discouraged by Akka for security
reasons (see `Java Security Guidelines`_ and `Perils of Java Deserialization`_).

For clusters and remote RaceActors RACE now defaults to a simple ``java.io.DataOutputStream`` and ``DataInputStream``
based approach that requires RACE configuration in order to whitelist serializable types and associate them with
respective ``gov.nasa.race.core.AkkaSerializers`` that provide implementations for two basic methods::

    def toBinary(o: AnyRef): Array[Byte]
    def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef

Serializers for standard RACE system messages can be found in ``gov.nasa.race.core.RaceSerializers`` and are generally
type specific, i.e. they only handle a single type which is uniquely identified in the receiving RACE system
by means of its Akka serializer id (that is transparently sent by Akka).




.. _Akka Clusters: https://doc.akka.io/docs/akka/current/typed/index-cluster.html
.. _cluster configuration: https://doc.akka.io/docs/akka/current/typed/cluster.html#configuration
.. _distributed data: https://doc.akka.io/docs/akka/current/typed/distributed-data.html
.. _Akka Remoting: https://doc.akka.io/docs/akka/current/remoting-artery.html
.. _Akka Serialization: https://doc.akka.io/docs/akka/current/serialization.html
.. _Java Serialization: https://docs.oracle.com/en/java/javase/16/docs/specs/serialization/
.. _Java Security Guidelines: https://www.oracle.com/java/technologies/javase/seccodeguide.html
.. _Perils of Java Deserialization: https://community.microfocus.com/cyberres/fortify/f/fortify-discussions/317555/the-perils-of-java-deserialization
.. _RaceActor Model: raceactors.rst
.. _Akka Classic: https://doc.akka.io/docs/akka/current/index-classic.html
.. _SHARE: `SHARE - System for Hierarchical Ad hoc Reporting`_
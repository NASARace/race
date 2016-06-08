Design Overview
===============
While RACE heavily depends on its Akka basis, RACE adds its own application model and actor types in
order to support

- fully runtime configurable systems
- deterministic system initialization and termination
- on-demand data flow control
- seamless communication between different RACE processes

Although fully compatible with normal Akka components, RACE instances are specialized Akka
applications. Moreover, while RACE instances can run as standalone processes, the application model
supports and encourages distributed operation, both for reasons of scalability and localized access
to external resources.

This section contains a high level description of the main RACE components and how they contribute
to the goals mentioned above. We start by looking at the main building blocks of RACE, from
the inside out.

.. image:: ../images/race-overview-2.svg
    :class: center scale60
    :alt: RACE overview


Main RACE Constructs
--------------------

**RaceActors** are the most basic RACE component. They are specialized Akka actors, implementing
a common state model (creation, initialization, start, pause/resume, termination). Among other
aspects, the common RACE type hierarchy supports instantiation and parameterization of RaceActors
from configuration data, separation of system- and user- message processing, and access to local and
global bus channels.

**Master Actor** is the supervisor of all RaceActors running within a RACE process. The master
is responsible for lifetime monitoring and control of all configured RaceActors. Each lifetime
phase (corresponding to RaceActor states) is processed sequentially by the master, according to
the order in which actors are specified in the configuration. Each phase is only entered after the
previous one succeeded, and within each phase actors are only processed after their successors
successfully completed this step.

**RaceActorSystem** is the construct that aggregates master, RaceActors and shared resources
such as the event bus and a system clock. RaceActorSystem objects also manage associated Akka
resources (Akka ActorSystem, logging etc.). Consequently, most of the RaceActorSystem processing
takes place during initialization and termination, such as creating the master actor during
RaceActorSystem initialization.

**RACE Drivers** are the main classes that start and control RACE. They are mostly responsible
for obtaining the configuration data and then instantiating a RaceActorSystem with this configuration.
RACE drivers are also the high level interface towards the user, providing commands to start, inspect
and terminate RACE as a whole, which is done by calling respective RaceActorSystem functions.
RACE contains a number of drivers, supporting user interaction through a text console, graphical
user interfaces and server interfaces. The driver layer also contains support to start remote
RACE instances without direct user input (e.g. for cloud deployment).

Armed with a basic understanding of main RACE constructs, we can now proceed looking at how these
components are used to achieve our high level goals.

Configuration
-------------
RACE is not a monolithic application. The various RACE drivers are generic applications that all
expect a configuration file (or object) that specifies the actors which partake in a concrete RACE
instance at runtime. To define such configurations, RACE uses a single, human readable text file
format (HOCON_). The main content of a configuration file is a ordered list of canonical RaceActor
specification elements, each one containing name, type, connections and type-specific parameters.
Those elements are passed into the respective RaceActor constructors.

Please refer to the `Runtime Configuration`_ section for more details.

Deterministic Runtime Sequences
-------------------------------
RACE is based on a state model that calls out separate creation, initialization, start
and termination phases. While Akka actors normally are supposed to avoid sequential processing
in favor of fully asynchronous operation, this makes it harder to define dependencies between actors.
Typical RACE applications use RaceActors_ to connect to external resources (data feeds and consumers),
and hence do have such dependencies between actors. RACE therefore enforces sequential system
construction and destruction by means of the master actor, based on the order in which RaceActors are
defined in their respective RACE configuration file.

In addition to initialization during actor construction, RACE also features a separate initialization
phase after all actors have been constructed, which is mostly used to connect and configure remote
RaceActors.

All this is in support of deterministic system behavior, which is a critical property for
simulations that should produce similar results under similar circumstances.

On-Demand Data Flow Control for Publish-Subscribe Channels
----------------------------------------------------------
RaceActors_ typically communicate through a anonymous publish/subscribe mechanism, i.e. producer
actors are connected to consumer actors by means of abstract *bus channels* that are specified as
path-like strings such as ``/flights/positions``. Each RaceActor_ is responsible for selecting what
information from a given channel it processes, which can be conveniently and safely programmed with
Scala's built-in `Pattern Matching`_ support. Bus channels use a hierarchical, path-like naming
scheme, which allows pattern based subscriptions.

While bus channels describe the static connections between actors, RACE also supports on-demand
flow control through fully transitive ChannelTopics_. This mechanism allows to turn on/off high
volume message processing that should only take place if there are active consumers, without
having to know *a priori* which actor is the original data source or requester.

Remoting
--------
RACE promotes distributed operation. Based on Akka's `Actor Remoting`_, RACE provides support
for full `Location Transparency`_ of RaceActors, which means that actors can be assigned
to different RACE instances strictly by means of configuration, without having to modify
the respective actor code.

Remoting encompasses two aspects: (a) specification of where the actor is going to physically
reside, and (b) a mechanism to let remote actors seamlessly communicate with local actors. The
first aspect is implemented by means of a ``remote`` configuration option that allows the
master actor to locate/start the remote actor. The seamless communication is achieved through
the master actor initialization phase that connects participating RACE instances, and through
the RaceActor publish/subscribe interface, which distinguishes between local and global bus by means
of channel names (e.g. ``/local/x``)


.. _Actor Remoting: http://doc.akka.io/docs/akka/current/scala/remoting.html
.. _Location Transparency: http://doc.akka.io/docs/akka/current/general/remoting.html#remoting
.. _HOCON: https://github.com/typesafehub/config/blob/master/HOCON.md
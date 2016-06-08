Layers and Modules
==================
The RACE distribution is divided into several modules that mostly act as layers, enforcing a
clear dependency relationship by means of a build system with separate compilation steps. Each
layer is only allowed to access lower layers.

From bottom to top:

**common** is the lowest layer that just serves the purpose of factoring out functions and data
types that are used by several higher layers, with the goal of avoiding redundant code. Apart from a
``gov.nasa.race.common`` package object that especially holds general control abstractions, this
layer mainly consists of xUtils objects, which are in turn just function modules
(``CryptUtils``,``FileUtils``,``NetUtils`` etc.). The common layer also contains the
``XmlPullParser``, which is a high performance non-validating XML pull parser that keeps track
of element nesting and hence is suitable for selective XML parsing.

**swing** is a special layer for common graphical user interface functions, which are based on
a Scala abstraction layer on top of ``javax.swing``. Apart from custom widgets this especially
holds support for CSS like styling of Swing components.

**core** is the layer that implements the basic runtime structure of RACE as described in the
RaceActors_ section, consisting of:

- ``RaceActor`` (and derived traits such as ``SubscribingRaceActor`` and ``PublishingRaceActor``)
- ``MasterActor`` (which instantiates and supervises configured RaceActors)
- ``RaceActorSystem`` (the aggregate object for master, bus and RaceActors)

The core layer also includes the bus implementation and system message definitions.

**data** is the first domain specific layer, which is supposed to hold common functions and types
for domain specific actors. It currently contains code for great circle calculations, generic
flight positions, and especially generic filters and translators that can be used to configure
generic import and translator actors.

**actors** contains the majority of RaceActors that are distributed with RACE. This module is
subdivided into various categories (imports,exports,bridges,filters,routers etc.) as it is supposed
to grow considerably.

**ww** is the module that provides an extensible geo-spatial viewer infrastructure based on
`NASA WorldWind`_. This includes not only the actor that creates and interfaces to a WorldWind
window, but also WorldWind-layer classes with associated actors to simplify display
of dynamic data received via RACE channels.

**tools** contains a number of main classes that support RACE end-users, such as encryption and
decryption of configuration files

**test-tools** holds convenience wrappers around some complex 3rd party servers RACE can interface
to, such as a ActiveMQ_ JMS server or a Kafka_ server

Last not least, the top level module holds the code for the various RACE drivers, including
``gov.nasa.race.ConsoleMain`` and the RemoteLauncher_ infrastructure.

Code for each layer (except of the toplevel) is located in a "race-<layer>" subdirectory, and
belongs to a ``gov.nasa.race.<layer>`` package. Each layer is compiled separately, making sure
it can only depend on lower layers.


.. _NASA WorldWind: https://goworldwind.org/
.. _ActiveMQ: http://activemq.apache.org/
.. _Kafka: http://kafka.apache.org/
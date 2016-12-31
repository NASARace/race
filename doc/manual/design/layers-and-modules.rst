Layers and Modules
==================
The RACE distribution is divided into several sub-modules that mostly act as layers, enforcing a
clear dependency relationship by means of a build system with separate compilation steps. Each
layer is only allowed to access lower layers.

From bottom to top:

``race-core`` - the basic layer with packages such as `gov.nasa.race.common`, `.core`, `.util`, `.config`, `.archive`.
`.main` and `.actor`. This includes the core classes (e.g. `RaceActor` and `RaceActorSystem`) as well as most of
the generic, domain-independent actors such as `TranslatorActor`. This layer is the minimum that needs to be
imported in external projects that use RACE.

``race-net-*`` - this is a collection of layers for importing and exporting from external messaging systems such
as ActiveMQ_ (JMS), Kafka_ and DDS_. Each of the modules mainly consists of import and export actors. Those modules
are kept separate because each of the 3rd party libraries they depend on have a potentially large fan out and hence
should only be included in an application that makes use of them.

``race-swing`` - this is a utility layer that adds user interface support that is not available in `javax.swing.*` or
scala-swing_, most notably configurable style support

``race-ww`` - is the layer which adds support for using `NASA WorldWind`_ to visualize geospatial data (e.g. flight
positions). This module contains both the `RaceViewerActor` that encapsulates WorldWind in RACE configs, and
the `RaceLayer` infrastructure to import and display dynamic RACE data into WorldWind layers.

``race-air`` - this is the first domain specific layer which includes support for airspace objects such as`FlightPos`
, `FlightPath` and `Airport`. This is also where the various SWIM_ message translators reside.

``race-ww-air`` - this layer adds WorldWind visualization for most of the `race-air` constructs

``race-testkit`` - provides RACE specific infrastructure to write test modules, e.g. to test RaceActors

``race-launcher`` - contains classes to launch and manage remote RACE instances

``race-ui`` - will hold a RACE console that makes use of a graphical user interface (as an alternative to the terminal
based `ConsoleMain` that is included in `race-core`)

``race-tools`` - is a layer that contains several stand-alone applications that support RACE development, most notably the
`CryptConfig` tool that is used to en-/de-crypt RACE config files (see `Using Encrypted Configurations`_ section).


.. _NASA WorldWind: https://goworldwind.org/
.. _ActiveMQ: http://activemq.apache.org/
.. _Kafka: http://kafka.apache.org/
.. _DDS: http://portals.omg.org/dds/
.. _scala-swing: https://github.com/scala/scala-swing
.. _SWIM: https://www.faa.gov/nextgen/programs/swim/
Using RACE from External Projects
=================================

RACE is a system that is meant to be extended by writing new actors, but those actors do not have to reside
within RACE. Extending RACE does not mean you have to clone and modify it - you can use RACE as a library.

Artifact Server and Dependency Definition
-----------------------------------------
Artifacts (jars) of various RACE `Layers and Modules`_ are published on the Central_Repository_ within the
``gov.nasa.race`` group, and can be imported into external projects by declaring them as normal dependencies within
their respective build configurations. For SBT_, a typical dependency definition in ``build.sbt`` for the latest
1.5.x release of RACE would be::

     libraryDependencies += "gov.nasa.race" %% "race-core" % "1.5.+"


Typical RACE module dependencies are

* ``race-core`` - the basic RACE module containing ``RaceActor``, ``RaceActorSystem`` and other core classes
* ``race-net-jms`` - the module providing actors to import from and export to JMS servers
* ``race-ww`` - provides the ``RaceViewerActor`` that encapsulates the `NASA WorldWind`_ geospatial viewer
* ``race-air`` - airspace simulation specific actors and other classes
* ``race-ww-air`` - `NASA WorldWind`_ based visualization for airspace objects such as flights and flight paths

Note that not every RACE revision is published on the Central_Repository_. If you need access to development snapshots
of RACE, you can publish it locally to avoid any changes in the build configuration of your project::

    $ git clone https://github.com/NASARace/race.git
    $ cd race
    $ sbt publishLocal

RACE and the client project(s) do not have to reside in the same directory tree.

Versioning Policy
-----------------
RACE uses a simple 3-level *<major>.<minor>.<micro>* versioning scheme. *<major>* release changes happen infrequently
and are mostly organizational, they are not directly related to compatibility. Within the same *<minor>* version public
API changes should be backwards compatible and should not require adaptation of external clients. Note that each lower
level number is reset when the parent level changes.

Typical version specifications in dependencies are:

* ``"latest.release"`` - the latest
* ``"1.5.+"`` - the latest version within the "1.5" line, to ensure API compatibility
* ``"1.5.3"`` - an explicit version


While version specifications for RACE modules could differ, it is recommended to use a single specification for all
modules, e.g. by defining a common ``raceVersion`` variable.


Example Client Project
----------------------
A minimal example that shows how to use RACE as a library can be found on https://github.com/NASARace/race-client-example.
This example consists of a single TLEActor_ which is written in Scala and displays satellite orbit positions for
configured satellites. The race-client-example_ project uses SBT_ as build system, the respective RACE dependencies
are ordinary SBT settings. The ``build.sbt`` looks like this::

     name := "race-client-example"
     scalaVersion := "2.12.1"

     // those settings are not RACE specific but recommended when running applications from within a SBT shell
     fork in run := true
     outputStrategy := Some(StdoutOutput)
     Keys.connectInput in run := true

     val raceVersion = "1.5.+"  // the latest revision within the "1.5" line

     lazy val root = (project in file(".")).
       enablePlugins(JavaAppPackaging). // provides 'stage' task to generate stand alone scripts that can be executed outside SBT
       settings(
         mainClass in Compile := Some("gov.nasa.race.main.ConsoleMain"),  // we just use RACEs driver
         libraryDependencies ++= Seq(
           "gov.nasa.race" %% "race-core" % raceVersion,
           "gov.nasa.race" %% "race-air" % raceVersion,
           "gov.nasa.race" %% "race-ww" % raceVersion,
           "gov.nasa.race" %% "race-ww-air" % raceVersion,
           // ... other dependencies
         )
     )

Note that this example project does not provide a main class of its own, it just re-uses the ``gov.nasa.race.main.ConsoleMain``
exported by RACE. The RACE artifacts are available on the usual ``maven.org`` servers and hence do not need any special
resolver configuration. Dependency specifications for other build systems such as Maven or Gradle can be found by visiting
https://search.maven.org and entering the required module (e.g. ``race-ww-air``).

There are no special considerations for external actors or ``*.conf`` files - they follow the same rules as laid out in
the RaceActors_ and `Runtime Configuration`_ sections.



.. _Central_Repository: http://central.sonatype.org/
.. _SBT: http://www.scala-sbt.org/
.. _race-client-example: https://github.com/NASARace/race-client-example
.. _TLEActor: https://github.com/NASARace/race-client-example/blob/master/src/main/scala/TLEActor.scala
.. _NASA WorldWind: https://worldwind.arc.nasa.gov/

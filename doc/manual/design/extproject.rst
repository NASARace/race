Using RACE from External Projects
=================================

RACE is a system that is meant to be extended by writing new actors, but those actors do not have to reside
within RACE. Extending RACE does not mean you have to clone and modify its directory structure.

Artifacts (jars) of various RACE `Modules and Layers`_ are published on the Central_Repository_, and can be imported into external
projects by declaring them as normal dependencies within their respective build configurations.

A minimal example that shows how to use RACE as a library can be found on https://github.com/NASARace/race-client-example.
This example consists of a single ``TLEActor`` which is written in Scala and displays satellite orbit positions for
configured satellites. This ``race-client-example`` project uses SBT_ as build system, the respective RACE dependencies
are ordinary SBT settings. The ``build.sbt`` looks like this::

     name := "race-client-example"
     scalaVersion := "2.12.1"

     // those settings are not RACE specific but recommended when running applications from within a SBT shell
     fork in run := true
     outputStrategy := Some(StdoutOutput)
     Keys.connectInput in run := true

     lazy val root = (project in file(".")).
       enablePlugins(JavaAppPackaging). // provides 'stage' task to generate stand alone scripts that can be executed outside SBT
       settings(
         mainClass in Compile := Some("gov.nasa.race.main.ConsoleMain"),  // we just use RACEs driver
         libraryDependencies ++= Seq(
           "gov.nasa" %% "race-core" % "1.+",
           "gov.nasa" %% "race-air" % "1.+",
           "gov.nasa" %% "race-ww" % "1.+",
           "gov.nasa" %% "race-ww-air" % "1.+",
           // ... other dependencies
         )
     )

Note that this example project does not provide a mainClass of its own, it just re-uses the one exported by RACE
(``ConsoleMain``). The RACE artifacts are available on the usual ``maven.org`` servers and hence do not need any special
resolver configuration. Dependency specifications for other build systems such as Maven or Gradle can be found by visiting
https://search.maven.org and entering the required module (e.g. ``race-ww-air``).

There are no special considerations for external actors or ``*.conf`` files - they follow the same rules as laid out in
the RaceActors_ and `Runtime Configuration`_ sections.



.. _Central_Repository: http://central.sonatype.org/
.. _SBT: http://www.scala-sbt.org/
/*
 * Copyright (c) 2016, United States Government, as represented by the
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

import sbt._

object Dependencies {

  // rev specs use Ivy2 notation (http://ant.apache.org/ivy/history/2.3.0/ivyfile/dependency.html#revision):
  //   1.2  : fixed
  //   1.+  : highest 1.x
  //   latest.<status>  (release/milestone/integration)
  //   [1.0,2.0[  : version ranges (using '[', ']', '(', ')' as range bounds)
  //   ..
  // NOTE - using "latest.release as the default is not the best choice for a production system, but
  // we want to get a feeling for how stable these components are
  //
  // NOTE - we don't use Ivy configuration mappings here since it is a usage attribute, i.e.
  // it should go into build.sbt (or wherever the moduleID is used). See the
  // 'libraryDependency in Test' caveat.

  //--- default libs

  //--- slf4j
  // NOTE - slf4j 1.8 now uses ServiceProvider and logback has not caught up as of 12/01/17
  val slf4jSimple = "org.slf4j" % "slf4j-simple" % "2.0.0-alpha0" // "1.7.26"

  //--- logback
  // does not support slf4j 1.8 (provider) yet
  val logback = "ch.qos.logback" % "logback-classic" %  "1.3.0-alpha4" // "1.2.3"

  //--- Typesafe config
  val typesafeConfig = "com.typesafe" % "config" % "1.3.3"  // akka still depends on 1.3.3

  //--- Scala parser combinators (https://github.com/scala/scala-parser-combinators)
  val scalaParser = "org.scala-lang.modules" %% "scala-parser-combinators" % "1.1.2"

  //--- scala-xml
  val scalaXml = "org.scala-lang.modules" %% "scala-xml" % "1.2.0"

  //--- new scala reflection (TypeTags etc.)
  val scalaReflect =  "org.scala-lang" % "scala-reflect" % CommonRaceSettings.scalaVer

  //--- scalaTags HTML generator
  val scalaTags = "com.lihaoyi" %% "scalatags" % "0.7.0"

  //--- scala automatic resource management (https://github.com/jsuereth/scala-arm)
  //val scalaArm = "com.jsuereth" %% "scala-arm" % "2.0"

  //--- scalanlp/breeze (numerical processing): https://github.com/scalanlp/breeze
  //val breeze = "org.scalanlp" %% "breeze" % "0.13.2"
  // val breezeNative = "org.scalanlp" %% "breeze-natives" % "0.13.2"

  //--- scalaTest
  val scalaTest = "org.scalatest" %% "scalatest" % "3.1.0-SNAP13"
  val flexmarkAll = "com.vladsch.flexmark" % "flexmark-all" % "0.35.10" // should be a scalaTest dependency but 3.1.0-SNAP13 is missing it
  val scalaTestPlus = "org.scalatestplus" %% "scalatestplus-scalacheck" % "1.0.0-SNAP8"

  val pegDown = "org.pegdown" % "pegdown" % "1.6.0" % Test

  //--- scalaCheck
  val scalaCheck = "org.scalacheck" %% "scalacheck" % "1.14.0" % Test

  val defaultLibs =  Seq(logback,typesafeConfig)
  val defaultTestLibs = Seq(scalaTest,scalaTestPlus,flexmarkAll,scalaCheck,pegDown)

  //--- Circe Json support
  val circeVersion = "0.12.0-RC4"
  val circeCore = "io.circe" %% "circe-core" % circeVersion
  val circeGeneric = "io.circe" %% "circe-generic" % circeVersion
  val circeParser = "io.circe" %% "circe-parser" % circeVersion

  val circeAll = Seq(circeCore,circeGeneric,circeParser)

  // Apache Avro serialization (for archiving/unarchiving)
  val avro = "org.apache.avro" % "avro" % "1.9.1"

  // scodec
  //val scodecBits = "org.scodec" %% "scodec-bits" % "1.1.5"
  //val scodecCore = "org.scodec" %% "scodec-core" % "1.10.3"
  //val scodecStream = "org.scodec" %% "scodec-stream" % "1.1.0-M9"
  //val scodecAll = Seq(scodecBits, scodecCore)

  //--- scala-swing
  val scalaSwing = "org.scala-lang.modules" %% "scala-swing" % "2.1.1"
  //val swingx = "org.swinglabs.swingx" % "swingx-core" % "1.6.5-1"
  //.. and possibly extensions for Tree and jfreechart

  //--- RSyntaxTextArea (TextEditor with syntax support)
  val rsTextArea = "com.fifesoft" % "rsyntaxtextarea" % "3.0.3"

  //--- the jfreechart plot and chart lib
  val jfreeChart = "org.jfree" % "jfreechart" % "1.5.0"

  //--- pure Java implementation of ssh2 (http://www.jcraft.com/jsch/)
  // NOTE this has to be a known version and verified instance so that we don't
  // enter credentials processed by a un-verified jar
  val jsch = "com.jcraft" % "jsch" % "0.1.55"

  //--- argon2 based password hashes ()
  val argon2 = "de.mkammerer" % "argon2-jvm" % "2.5"

  //--- Akka
  val akkaVersion = "2.5.25"
  val akkaOrg = "com.typesafe.akka"

  val akkaActor = akkaOrg %% "akka-actor" % akkaVersion
  val akkaRemote = akkaOrg %% "akka-remote" % akkaVersion
  val akkaSlf4j = akkaOrg %% "akka-slf4j" % akkaVersion

  val akkaTestkit = akkaOrg %% "akka-testkit" % akkaVersion
  val akkaMultiNodeTestkit = akkaOrg %% "akka-multi-node-testkit" % akkaVersion // % "test,multi-jvm"

  val akkaHttp = akkaOrg %% "akka-http" % "10.1.9"

  val akkaAll = Seq(akkaActor)


  //--- ActiveMQ
  val amqVersion = "5.15.10"
  val amqOrg = "org.apache.activemq"
  val amqBroker = amqOrg % "activemq-broker" % amqVersion

  val amqAll = Seq(amqBroker)

  // here it gets ugly - kafka/zookeeper have hard log4j dependencies, although those
  // might be just misconfigurations since it also appeared under the slf4j dep
  // and hence might be only a runtime resolved backend.
  // If the real log4j is in the classpath, slf4j is going to annoy us with the notorious
  // "multiple bindings" error/warning during static init, so we have to make sure we
  // replace log4j with slf4j's log4j-over-slf4j

  // note - kafka still uses the old log4j

  val log4j = "org.apache.logging.log4j" % "log4j" % "2.10.0"
  val log4jOverSlf4j = "org.slf4j" % "log4j-over-slf4j" % "1.8.0-beta4"

  //--- ZooKeeper
  val zookeeper = "org.apache.zookeeper" % "zookeeper" % "3.5.5" excludeAll(
    ExclusionRule(organization = "log4j", name="log4j"),
    ExclusionRule(organization = "org.slf4j", name = "slf4j-log4j12")
  )

  //--- Kafka client (this is all we need for importing/exporting)
  // note that clients are NOT upward compatible, i.e. a new client doesn't work with an old server
  // (the new server with old client is supposedly fine). Since there are many old servers out there, we can't
  // use the latest client yet

  // note - we can't move to 1.1.0 yet since it breaks KafkaServer (again)
  val kafkaClients = "org.apache.kafka" % "kafka-clients" % "0.9.0.0"
  val newKafkaClients = "org.apache.kafka" % "kafka-clients" % "2.2.1"

  //--- Kafka (make sure to add log4j to kafkaServer dependencies
  val kafka = "org.apache.kafka" % "kafka_2.12" % "2.3.0" excludeAll(
  //val kafka = "org.apache.kafka" %% "kafka" % "1.0.1" excludeAll(
    //ExclusionRule(organization = "log4j", name="log4j"),
    //ExclusionRule(organization = "org.slf4j", name = "slf4j-log4j12")
  )

  //--- DDS Java 5 PSM
  // add implementation libraries and settings in local-build.sbt - this is only an abstract interface for compilation
  val omgDDS = "org.omg.dds" % "java5-psm" % "1.0"

  //--- LWJGL Java wrapper for OpenGL,Vulkan,,OpenCL,OpenAL and others
  val lwjglVersion = "3.2.3"
  lazy val lwjglNativeClassifier = OS.build match {
    case _:Linux => "natives-linux"
    case _:OSX => "natives-macos"
    case _:Windows => "natives-windows"
    case os:Unknown => throw new RuntimeException(s"OS ${os.name} not supported by LWJGL")
  }

  lazy val lwjglBase = "org.lwjgl" % "lwjgl" % lwjglVersion
  lazy val lwjglOpenCL = "org.lwjgl" % "lwjgl-opencl" % lwjglVersion
  lazy val lwjglNative = "org.lwjgl" % "lwjgl" % lwjglVersion classifier lwjglNativeClassifier


  //--- publishable WorldWindJava version
  val worldwindPcm = "com.github.pcmehlitz" % "worldwind-pcm" % "2.1.0.198"


  //--- this is used from build.sbt to add dependency resolvers

  val dependencyResolvers: Seq[Resolver] = Nil

  //val sonatypeNexusSnapshots = "Sonatype Nexus Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots" // squants
  //val pcmSnapshots = "Local Maven Repository" at "file://"+Path.userHome.absolutePath+ "/git/pcm-mvn/snapshots"
  //val dependencyResolvers: Seq[Resolver] = Seq(
  //  sonatypeNexusSnapshots
  //)
}

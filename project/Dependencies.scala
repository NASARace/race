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
  val slf4jSimple = "org.slf4j" % "slf4j-simple" % "latest.release"

  //--- logback
  val logback = "ch.qos.logback" % "logback-classic" % "latest.release"

  //--- Typesafe config
  val typesafeConfig = "com.typesafe" % "config" % "latest.release"

  //--- nscala-time (Dates with operators): https://github.com/nscala-time/nscala-time
  val nscalaTime = "com.github.nscala-time" %% "nscala-time" %  "latest.release"

  //--- Scala parser combinators (https://github.com/scala/scala-parser-combinators)
  val scalaParser = "org.scala-lang.modules" % "scala-parser-combinators_2.12" % "latest.release"

  //--- scala-xml
  val scalaXml = "org.scala-lang.modules" % "scala-xml_2.12" % "latest.release"

  //--- new scala reflection (TypeTags etc.)
  val scalaReflect =  "org.scala-lang" % "scala-reflect" % "2.12.2"

  //--- scalaTags HTML generator
  val scalaTags = "com.lihaoyi" %% "scalatags" % "latest.release"

  //--- scala automatic resource management (https://github.com/jsuereth/scala-arm) - 2.12.0 not yet supported
  //val scalaArm = "com.jsuereth" %% "scala-arm" % "1.4"

  //--- miniboxing (better form of AnyVal specialization)
  //val scalaMiniboxingRT = "org.scala-miniboxing.plugins" %% "miniboxing-runtime" % "0.4-SNAPSHOT"
  //val scalaMiniboxingCompile = "org.scala-miniboxing.plugins" %% "miniboxing-plugin" % "0.4-SNAPSHOT"

  //--- scalanlp/breeze (numerical processing): https://github.com/scalanlp/breeze
  //val breeze = "org.scalanlp" %% "breeze" % "latest.release"
  // val breezeNative = "org.scalanlp" %% "breeze-natives" % "latest.release"

  //--- scalaTest
  //val scalaTest = "org.scalatest" %% "scalatest" % "2.2.6"
  val scalaTest = "org.scalatest" % "scalatest_2.12" % "3.0.1"
  val pegDown = "org.pegdown" % "pegdown" % "latest.release"

  //--- scalaCheck
  val scalaCheck = "org.scalacheck" % "scalacheck_2.12" % "latest.release" // 1.13.4"

  // liftJson
  val liftJson = "net.liftweb" %% "lift-json" % "latest.release"
  val liftJsonExt = "net.liftweb" %% "lift-json-ext" % "latest.release"
  val liftJsonAll = Seq(liftJson,liftJsonExt)

  val defaultLibs =  Seq(logback,typesafeConfig,nscalaTime,liftJson)
  val defaultTestLibs = Seq(scalaTest,scalaCheck,pegDown)

  // scodec
  val scodecBits = "org.scodec" %% "scodec-bits" % "latest.release"
  val scodecCore = "org.scodec" %% "scodec-core" % "latest.release"
  //val scodecStream = "org.scodec" %% "scodec-stream" % "0.10.0"  // latest.release not yet on resolvers
  val scodecAll = Seq(scodecBits, scodecCore)

  //--- scala-swing
  val scalaSwing = "org.scala-lang.modules" %% "scala-swing" % "latest.release"
  //val swingx = "org.swinglabs.swingx" % "swingx-core" % "latest.release"
  //.. and possibly extensions for Tree and jfreechart

  //--- RSyntaxTextArea (TextEditor with syntax support)
  val rsTextArea = "com.fifesoft" % "rsyntaxtextarea" % "2.5.8"

  //--- the jfreechart plot and chart lib
  val jfreeChart = "org.jfree" % "jfreechart" % "latest.release"

  //--- pure Java implementation of ssh2 (http://www.jcraft.com/jsch/)
  // NOTE this has to be a known version and verified instance so that we don't
  // enter credentials processed by a un-verified jar
  val jsch = "com.jcraft" % "jsch" % "0.1.54"

  //--- argon2 based password hashes ()
  val argon2 = "de.mkammerer" % "argon2-jvm" % "latest.release"

  //--- Akka
  val akkaVersion = "latest.release"
  val akkaOrg = "com.typesafe.akka"

  val akkaActor = akkaOrg %% "akka-actor" % akkaVersion
  val akkaRemote = akkaOrg %% "akka-remote" % akkaVersion
  val akkaSlf4j = akkaOrg %% "akka-slf4j" % akkaVersion

  val akkaTestkit = akkaOrg %% "akka-testkit" % akkaVersion
  val akkaMultiNodeTestkit = akkaOrg %% "akka-multi-node-testkit" % akkaVersion // % "test,multi-jvm"

  val akkaHttp = akkaOrg %% "akka-http" % "latest.release"

  val akkaAll = Seq(akkaActor)


  //--- ActiveMQ
  val amqVersion = "latest.release"  // >= 5.11.1
  val amqOrg = "org.apache.activemq"
  val amqBroker = amqOrg % "activemq-broker" % amqVersion

  val amqAll = Seq(amqBroker)

  // here it gets ugly - kafka/zookeeper have hard log4j dependencies, although those
  // might be just misconfigurations since it also appeared under the slf4j dep
  // and hence might be only a runtime resolved backend.
  // If the real log4j is in the classpath, slf4j is going to annoy us with the notorious
  // "multiple bindings" error/warning during static init, so we have to make sure we
  // replace log4j with slf4j's log4j-over-slf4j

  val log4j = "log4j" % "log4j" % "latest.release"
  val log4jOverSlf4j = "org.slf4j" % "log4j-over-slf4j" % "latest.release"

  //--- ZooKeeper
  val zookeeper = "org.apache.zookeeper" % "zookeeper" % "latest.release" excludeAll(
    ExclusionRule(organization = "log4j", name="log4j"),
    ExclusionRule(organization = "org.slf4j", name = "slf4j-log4j12")
  )

  //--- Kafka client (this is all we need for importing/exporting)
  // note that clients are not upward compatible, i.e. a new client doesn't work with an old server
  // (the new server with old client is supposedly fine). Since there are many old servers out there, we can't
  // use the latest client yet
  val kafkaClients = "org.apache.kafka" % "kafka-clients" % "0.9.0.0"
  val newKafkaClients = "org.apache.kafka" % "kafka-clients" % "latest.release"

  //--- Kafka (make sure to add log4j to kafkaServer dependencies
  val kafka = "org.apache.kafka" %% "kafka" % "latest.release" excludeAll(
    ExclusionRule(organization = "log4j", name="log4j"),
    ExclusionRule(organization = "org.slf4j", name = "slf4j-log4j12")
  )

  //--- DDS Java 5 PSM
  // add implementation libraries and settings in local-build.sbt - this is only an abstract interface for compilation
  val omgDDS = "org.omg.dds" % "java5-psm" % "1.0"

  //--- publishable WorldWindJava version
  val worldwindPcm = "com.github.pcmehlitz" % "worldwind-pcm" % "2.1.0.+"

  //--- this is used from build.sbt to add dependency resolvers

  val dependencyResolvers: Seq[Resolver] = Nil

  //val sonatypeNexusSnapshots = "Sonatype Nexus Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots" // squants
  //val pcmSnapshots = "Local Maven Repository" at "file://"+Path.userHome.absolutePath+ "/git/pcm-mvn/snapshots"
  //val dependencyResolvers: Seq[Resolver] = Seq(
  //  sonatypeNexusSnapshots
  //)
}

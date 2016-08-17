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
  val typesafeConfig = "com.typesafe" % "config" % "1.3.0"

  //--- nscala-time (Dates with operators): https://github.com/nscala-time/nscala-time
  val nscalaTime = "com.github.nscala-time" %% "nscala-time" %  "latest.release"

  //--- Scala XML (sep. library as of 2.11.6)
  val scalaXml =  "org.scala-lang.modules" %% "scala-xml" % "latest.release" // >= 1.0.3

  //--- Scala parser combinators (https://github.com/scala/scala-parser-combinators)
  val scalaParser =  "org.scala-lang.modules" %% "scala-parser-combinators"  % "latest.release"

  //--- new scala reflection (TypeTags etc.)
  val scalaReflect =  "org.scala-lang" % "scala-reflect" % "2.11.7"

  //--- scala automatic resource management (https://github.com/jsuereth/scala-arm)
  val scalaArm = "com.jsuereth" %% "scala-arm" % "1.4"

  //--- miniboxing (better form of AnyVal specialization)
  //val scalaMiniboxingRT = "org.scala-miniboxing.plugins" %% "miniboxing-runtime" % "0.4-SNAPSHOT"
  //val scalaMiniboxingCompile = "org.scala-miniboxing.plugins" %% "miniboxing-plugin" % "0.4-SNAPSHOT"

  //--- squants (Scala quantities): https://github.com/garyKeorkunian/squants
  val squants = "com.squants"  %% "squants"  % "0.6.2"

  //--- scalanlp/breeze (numerical processing): https://github.com/scalanlp/breeze
  val breeze = "org.scalanlp" %% "breeze" % "latest.release"
  // val breezeNative = "org.scalanlp" %% "breeze-natives" % "latest.release"

  //--- scalaTest
  // "latest.release" picks up snapshot, which seems to be incompatible (ShouldBeMatcher etc.)
  val scalaTest = "org.scalatest" %% "scalatest" % "2.2.6"
  val pegDown = "org.pegdown" % "pegdown" % "latest.release"

  //--- scalaCheck
  // "latest.release" (1.13.0) seems to be incompatible with scalaTest 2.2.6
  val scalaCheck = "org.scalacheck" %% "scalacheck" % "1.12.5"

  // scopt command line parsing https://github.com/scopt/scopt
  val scopt = "com.github.scopt" %% "scopt" % "latest.release"

  // liftJson
  val liftJson = "net.liftweb" %% "lift-json" % "latest.release"

  val defaultLibs =  Seq(logback,typesafeConfig,nscalaTime,scalaXml,liftJson,squants)
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
  val jfreechart = "org.jfree" % "jfreechart" % "latest.release"

  //--- pure Java implementation of ssh2 (http://www.jcraft.com/jsch/)
  // NOTE this has to be a known version and verified instance so that we don't
  // enter credentials processed by a un-verified jar
  val jsch = "com.jcraft" % "jsch" % "0.1.53"

  //--- worldwind (a external sub project for now)
  //val worldwind = "gov.nasa" % "worldwind" % "latest.integration" // "2.0.0-986"


  // the maven repo does not properly configure dependencies, which are jogl, gluegen and gdal
  // (note we don't need that if we properly publish WW local)
  val jogl = "org.jogamp.jogl" % "jogl-all-main" % "2.1.5-01"
  val gluegen = "org.jogamp.gluegen" % "gluegen-rt-main" % "2.1.5-01"
  val gdal = "org.gdal" % "gdal" % "1.11.2"

  //--- Akka
  val akkaVersion = "latest.release"
  val akkaOrg = "com.typesafe.akka"

  val akkaActor = akkaOrg %% "akka-actor" % akkaVersion
  val akkaRemote = akkaOrg %% "akka-remote" % akkaVersion
  val akkaSlf4j = akkaOrg %% "akka-slf4j" % akkaVersion


  val akkaTestkit = akkaOrg %% "akka-testkit" % akkaVersion
  val akkaMultiNodeTestkit = akkaOrg %% "akka-multi-node-testkit" % akkaVersion // % "test,multi-jvm"

  val akkaAll = Seq(akkaSlf4j,akkaActor)


  //--- ActiveMQ
  val amqVersion = "latest.release"  // >= 5.11.1
  val amqOrg = "org.apache.activemq"
  val amqBroker = amqOrg % "activemq-broker" % amqVersion

  val amqAll = Seq(amqBroker)

  //--- AsyncHttpClient
  val asyncHttp = "com.ning" % "async-http-client" % "1.9.27"

  // here it gets ugly - kafka/zookeeper have hard log4j dependencies, although those
  // might be just misconfigurations since it also appeared under the slf4j dep
  // and hence might be only a runtime resolved backend.
  // If the real log4j is in the classpath, slf4j is going to annoy us with the notorious
  // "multiple bindings" error/warning during static init, so we have to make sure we
  // replace log4j with slf4j's log4j-over-slf4j

  val log4j = "log4j" % "log4j" % "latest.release"
  val log4jOverSlf4j = "org.slf4j" % "log4j-over-slf4j" % "latest.release"

  //--- akka-kafka
  val akkaKafka = "com.sclasen" %% "akka-kafka" % "latest.release" excludeAll(
    ExclusionRule(organization = "log4j", name="log4j"),
    ExclusionRule(organization = "org.slf4j", name = "slf4j-log4j12")
  )

  //--- ZooKeeper ("3.4.7" works, "latest.release" includes alphas!)
  val zookeeper = "org.apache.zookeeper" % "zookeeper" % "3.4.7" excludeAll(
    ExclusionRule(organization = "log4j", name="log4j"),
    ExclusionRule(organization = "org.slf4j", name = "slf4j-log4j12")
  )

  //--- Kafka (make sure to add log4j to kafkaServer dependencies (0.9.0.0 works)
  val kafka = "org.apache.kafka" %% "kafka" % "latest.release" excludeAll(
    ExclusionRule(organization = "log4j", name="log4j"),
    ExclusionRule(organization = "org.slf4j", name = "slf4j-log4j12")
  )


  //--- this is used from build.sbt to add dependency resolvers
  val sonatypeNexusSnapshots = "Sonatype Nexus Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots" // squants
  //val pcmSnapshots = "Local Maven Repository" at "file://"+Path.userHome.absolutePath+ "/git/pcm-mvn/snapshots"

  val dependencyResolvers: Seq[Resolver] = Seq(
    sonatypeNexusSnapshots
  )
}

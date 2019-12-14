// SBT build definition for RACE root project 

//--- imports from project
import RaceBuild._
import Dependencies._
import CommonRaceSettings._

import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys._


shellPrompt in ThisBuild := { state => "[" + Project.extract(state).currentRef.project + "]> " }

lazy val commonSettings = commonRaceSettings ++ Seq(
  organization := "gov.nasa.race",
  version := "1.6.0"
)

lazy val testSettings = commonSettings ++ noPublishSettings  // test projects don't publish artifacts

//--- root project (only for aggregation)
lazy val root = createRootProject("race").
  aggregate(raceCore,raceNetJMS,raceNetKafka,raceNetDDS,raceNetHttp,raceSwing,raceWW,raceAir,raceWWAir,raceSpace,raceLauncher,raceAdapter,
    raceCL,raceTools,raceTestKit,raceCoreTest,raceNetJMSTest,raceNetHttpTest,raceNetKafkaTest,raceCLTest,raceAirTest,raceSpaceTest).
  dependsOn(raceCore,raceNetJMS,raceNetKafka,raceNetDDS,raceNetHttp,raceSwing,raceWW,raceAir,raceWWAir,raceSpace,raceLauncher).
  enablePlugins(JavaAppPackaging,LauncherJarPlugin,LaikaPlugin).
  settings(
    commonSettings,
    Defaults.itSettings,
    commands ++= LaikaCommands.commands,
    aggregate in MultiJvm := false,
    mainClass in Compile := Some("gov.nasa.race.main.ConsoleMain"),
    noPublishSettings // root does not publish any artifacts
  ).
  addLibraryDependencies(slf4jSimple,akkaSlf4j)  // in case somebody wants to configure SLF4J logging

//--- sub projects

//--- those are the projects that produce build artifacts which can be used by 3rd party clients

lazy val raceCore = createProject("race-core", commonSettings).
  enablePlugins(JavaAppPackaging,LauncherJarPlugin).
  settings(
    mainClass in Compile := Some("gov.nasa.race.main.ConsoleMain")
  ).
  addLibraryDependencies(akkaActor,akkaRemote,typesafeConfig,scalaReflect,jsch,scalaXml,avro)

lazy val raceLauncher = createProject("race-launcher", commonSettings).
  dependsOn(raceCore).
  enablePlugins(JavaAppPackaging,LauncherJarPlugin).
  settings(
    mainClass in Compile := Some("gov.nasa.race.remote.ConsoleRemoteLauncher")
  ).
  addLibraryDependencies(typesafeConfig)

lazy val raceNetJMS = createProject("race-net-jms", commonSettings).
  dependsOn(raceCore).
  addLibraryDependencies(akkaAll,amqBroker,slf4jSimple,akkaSlf4j)

// unfortunately the 1.0 kafka clients are not source compatible
lazy val raceNetKafka = createProject("race-net-kafka", commonSettings).
  dependsOn(raceCore).
  addLibraryDependencies(kafkaClients) // per default we still build with the 0.9 kafka-client

lazy val raceNetDDS = createProject("race-net-dds", commonSettings).
  dependsOn(raceCore).
  makeExtensible.
  addLibraryDependencies(omgDDS)

lazy val raceNetHttp = createProject("race-net-http", commonSettings).
  dependsOn(raceCore).
  addLibraryDependencies(akkaHttp,scalaTags,scalaTags,argon2,jfreeChart)

lazy val raceSwing = createProject("race-swing", commonSettings).
  dependsOn(raceCore).
  addLibraryDependencies(akkaActor,scalaSwing,rsTextArea)

lazy val raceAir = createProject("race-air", commonSettings).
  dependsOn(raceCore,raceNetJMS,raceNetHttp).
  addLibraryDependencies(akkaActor,typesafeConfig,scalaParser)

lazy val raceWW = createProject("race-ww", commonSettings).
  dependsOn(raceCore,raceSwing).
  addLibraryDependencies(akkaAll,scalaSwing,worldwindPcm)

lazy val raceWWAir = createProject("race-ww-air", commonSettings).
  dependsOn(raceWW,raceAir).
  addLibraryDependencies(typesafeConfig)

lazy val raceCL = createProject("race-cl", commonSettings).
  enablePlugins(JavaAppPackaging,ClasspathJarPlugin).
  dependsOn(raceCore).
  addLibraryDependencies(lwjglBase,lwjglOpenCL,lwjglNative)

lazy val raceTestKit = createProject("race-testkit", commonSettings).
  dependsOn(raceCore).
  addLibraryDependencies(defaultTestLibs,akkaActor,akkaTestkit,akkaMultiNodeTestkit)

lazy val raceUI = createProject("race-ui", commonSettings).
  dependsOn(raceCore,raceSwing)

lazy val raceTools = createProject("race-tools", commonSettings).
  enablePlugins(JavaAppPackaging,ClasspathJarPlugin).
  dependsOn(raceCore,raceNetHttp,raceAir).
  settings(
    mainClass in Compile := Some("gov.nasa.race.tool.CryptConfig")).
  addLibraryDependencies(logback,avro)

lazy val raceSpace = createProject("race-space", commonSettings).
  dependsOn(raceCore).
  settings(
    noPublishSettings // not yet published
  )

lazy val raceAdapter = createProject("race-adapter", commonSettings).
  // no dependencies - this is code supposed to be imported into external projects
  settings(
    noPublishSettings // not yet published (and only will publish Java)
  )

//--- test projects - no artifacts, only used to test this repository

lazy val raceTestKitTest = createTestProject("race-testkit-test", testSettings).
  enablePlugins(MultiJvmPlugin).
  dependsOn(raceCore,raceTestKit).
  configs(MultiJvm)

lazy val raceCoreTest = createTestProject("race-core-test", testSettings).
  dependsOn(raceCore,raceTestKit)

lazy val raceNetJMSTest = createTestProject("race-net-jms-test", testSettings).
  enablePlugins(MultiJvmPlugin).
  dependsOn(raceNetJMS,raceTestKit).
  configs(MultiJvm).
  settings(
    mainClass in Compile := Some("gov.nasa.race.jms.JMSServer")
  ).
  addLibraryDependencies(logback,akkaSlf4j,akkaRemote)

lazy val raceNetHttpTest = createTestProject("race-net-http-test", testSettings).
  dependsOn(raceNetHttp,raceTestKit)

lazy val raceNetKafkaTest = createTestProject("race-net-kafka-test", testSettings).
  enablePlugins(JavaAppPackaging,MultiJvmPlugin).
  dependsOn(raceNetKafka,raceTestKit).
  configs(MultiJvm).
  settings(
    mainClass in Compile := Some("gov.nasa.race.kafka.KafkaServer"),
    dependencyOverrides += newKafkaClients
  ).
  addLibraryDependencies(slf4jSimple,log4jOverSlf4j,zookeeper,newKafkaClients,kafka).
  addTestLibraryDependencies(akkaSlf4j)

lazy val raceNetDDSTest = createTestProject("race-net-dds-test", testSettings).
  enablePlugins(JavaAppPackaging,MultiJvmPlugin).
  dependsOn(raceNetDDS,raceTestKit,raceAir).
  configs(MultiJvm).
  settings(
    mainClass in Compile := Some("gov.nasa.race.dds.DDSServer")
  )

lazy val raceCLTest = createTestProject("race-cl-test", testSettings).
  enablePlugins(JavaAppPackaging,ClasspathJarPlugin).
  dependsOn(raceCL,raceTestKit).
  settings(
    fork := true
  )

lazy val raceAirTest = createTestProject("race-air-test", testSettings).
  dependsOn(raceAir,raceTestKit).
  addLibraryDependencies(circeAll)

lazy val raceSpaceTest = createTestProject("race-space-test", testSettings).
  dependsOn(raceSpace,raceTestKit)

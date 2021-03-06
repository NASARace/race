// SBT build definition for RACE root project 

//--- imports from project
import RaceBuild._
import Dependencies._
import CommonRaceSettings._

ThisBuild / shellPrompt := { state => "[" + Project.extract(state).currentRef.project + "]> " }

enablePlugins(LaikaPlugin)

lazy val commonSettings = commonRaceSettings ++ Seq(
  organization := "gov.nasa.race",
  version := "1.8.0"
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
    Compile / mainClass := Some("gov.nasa.race.main.ConsoleMain"),
    noPublishSettings // root does not publish any artifacts
  ).
  addLibraryDependencies(slf4jSimple,akkaSlf4j)  // in case somebody wants to configure SLF4J logging

//--- sub projects

//--- those are the projects that produce build artifacts which can be used by 3rd party clients

lazy val raceCore = createProject("race-core", commonSettings).
  enablePlugins(JavaAppPackaging,LauncherJarPlugin).
  settings(
    Compile / mainClass := Some("gov.nasa.race.main.ConsoleMain")
  ).
  addLibraryDependencies(akkaActor,akkaRemoting,typesafeConfig,scalaReflect,jsch,avro,jimfs)

lazy val raceLauncher = createProject("race-launcher", commonSettings).
  dependsOn(raceCore).
  enablePlugins(JavaAppPackaging,LauncherJarPlugin).
  settings(
    Compile / mainClass := Some("gov.nasa.race.remote.ConsoleRemoteLauncher")
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
  addLibraryDependencies(akkaHttp,scalaTags,scalaTags,argon2,jfreeChart,webauthn)

lazy val raceSwing = createProject("race-swing", commonSettings).
  dependsOn(raceCore).
  settings(
    Compile / packageBin / mappings += {
      // we have to add this classfile explicitly since it has to be pre-compiled on macOS
      (baseDirectory.value / "lib" / "gov/nasa/race/swing/MacOSHelper.class") -> "gov/nasa/race/swing/MacOSHelper.class"
    },
    Compile / unmanagedClasspath += baseDirectory.value / "lib"
  ).
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
  addLibraryDependencies(defaultTestLibs,akkaActor,akkaTestkit)

lazy val raceUI = createProject("race-ui", commonSettings).
  dependsOn(raceCore,raceSwing)

lazy val raceTools = createProject("race-tools", commonSettings).
  enablePlugins(JavaAppPackaging,ClasspathJarPlugin).
  dependsOn(raceCore,raceNetHttp,raceAir).
  settings(
    Compile / mainClass := Some("gov.nasa.race.tool.CryptConfig")).
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
  dependsOn(raceCore,raceTestKit)

lazy val raceCoreTest = createTestProject("race-core-test", testSettings).
  dependsOn(raceCore,raceTestKit)

lazy val raceNetJMSTest = createTestProject("race-net-jms-test", testSettings).
  dependsOn(raceNetJMS,raceTestKit).
  settings(
    Compile / mainClass := Some("gov.nasa.race.jms.JMSServer")
  ).
  addLibraryDependencies(logback,akkaSlf4j,akkaRemote)

lazy val raceNetHttpTest = createTestProject("race-net-http-test", testSettings).
  dependsOn(raceNetHttp,raceTestKit)

lazy val raceNetKafkaTest = createTestProject("race-net-kafka-test", testSettings).
  enablePlugins(JavaAppPackaging).
  dependsOn(raceNetKafka,raceTestKit).
  settings(
    Compile / mainClass := Some("gov.nasa.race.kafka.KafkaServer")
  ).
  addLibraryDependencies(slf4jSimple,log4jOverSlf4j,kafkaAll).
  addTestLibraryDependencies(akkaRemoting,akkaSlf4j)

lazy val raceNetDDSTest = createTestProject("race-net-dds-test", testSettings).
  enablePlugins(JavaAppPackaging).
  dependsOn(raceNetDDS,raceTestKit,raceAir).
  settings(
    Compile / mainClass := Some("gov.nasa.race.dds.DDSServer")
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

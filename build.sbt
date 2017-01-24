// SBT build definition for RACE root project 

//--- imports from project
import RaceBuild._
import Dependencies._
import CommonRaceSettings._

shellPrompt in ThisBuild := { state => "[" + Project.extract(state).currentRef.project + "]> " }

lazy val commonSettings = commonRaceSettings ++ Seq(
  organization := "gov.nasa.race",
  version := "1.3.2"
)

lazy val testSettings = commonSettings ++ noPublishSettings  // test projects don't publish artifacts


//--- root project (only for aggregation)
lazy val root = createRootProject("race").
  aggregate(raceCore,raceNetJMS,raceNetKafka,raceNetDDS,raceSwing,raceWW,raceAir,raceWWAir,raceSpace,raceLauncher,
    raceTools,raceTestKit,raceCoreTest,raceNetJMSTest,raceNetKafkaTest,raceAirTest).
  dependsOn(raceCore,raceNetJMS,raceNetKafka,raceNetDDS,raceSwing,raceWW,raceAir,raceWWAir,raceSpace,raceLauncher).
  enablePlugins(JavaAppPackaging,LauncherJarPlugin).
  settings(
    commonSettings,
    Defaults.itSettings,
    commands ++= LaikaCommands.commands,
    aggregate in MultiJvm := false,
    mainClass in Compile := Some("gov.nasa.race.main.ConsoleMain"),
    noPublishSettings // root does not publish any artifacts
  )

//--- sub projects

//--- those are the projects that produce build artifacts which can be used by 3rd party clients

lazy val raceCore = createProject("race-core", commonSettings).
  enablePlugins(JavaAppPackaging,LauncherJarPlugin).
  settings(
    mainClass in Compile := Some("gov.nasa.race.main.ConsoleMain")
  ).
  addLibraryDependencies(akkaActor,akkaRemote,typesafeConfig,nscalaTime,scalaReflect,jsch)

lazy val raceLauncher = createProject("race-launcher", commonSettings).
  dependsOn(raceCore).
  enablePlugins(JavaAppPackaging,LauncherJarPlugin).
  settings(
    mainClass in Compile := Some("gov.nasa.race.remote.ConsoleRemoteLauncher")
  ).
  addLibraryDependencies(typesafeConfig)

lazy val raceNetJMS = createProject("race-net-jms", commonSettings).
  dependsOn(raceCore).
  addLibraryDependencies(akkaAll,amqBroker)

lazy val raceNetKafka = createProject("race-net-kafka", commonSettings).
  dependsOn(raceCore).
  addLibraryDependencies(logback,akkaSlf4j,log4jOverSlf4j,kafka)

lazy val raceNetDDS = createProject("race-net-dds", commonSettings).
  dependsOn(raceCore).
  makeExtensible.
  addLibraryDependencies(omgDDS)

lazy val raceNetHttp = createProject("race-net-http", commonSettings).
  dependsOn(raceCore).
  addLibraryDependencies(asyncHttp)

lazy val raceSwing = createProject("race-swing", commonSettings).
  dependsOn(raceCore).
  addLibraryDependencies(akkaActor,scalaSwing,rsTextArea)

lazy val raceAir = createProject("race-air", commonSettings).
  dependsOn(raceCore,raceNetJMS).
  addLibraryDependencies(akkaActor,typesafeConfig,nscalaTime,scalaParser,scodecAll)

lazy val raceWW = createProject("race-ww", commonSettings).
  dependsOn(raceCore,raceSwing).
  addLibraryDependencies(akkaAll,scalaSwing,worldwindPcm)

lazy val raceWWAir = createProject("race-ww-air", commonSettings).
  dependsOn(raceWW,raceAir).
  addLibraryDependencies(typesafeConfig,nscalaTime)

lazy val raceTestKit = createProject("race-testkit", commonSettings).
  dependsOn(raceCore).
  addLibraryDependencies(defaultTestLibs,nscalaTime,akkaActor,akkaTestkit,akkaMultiNodeTestkit)

lazy val raceUI = createProject("race-ui", commonSettings).
  dependsOn(raceCore,raceSwing)

lazy val raceTools = createProject("race-tools", commonSettings).
  enablePlugins(JavaAppPackaging,ClasspathJarPlugin).
  dependsOn(raceCore).
  settings(
    mainClass in Compile := Some("gov.nasa.race.tool.CryptConfig")).
  addLibraryDependencies(logback)

lazy val raceSpace = createProject("race-space", commonSettings).
  dependsOn(raceCore).
  settings(
    noPublishSettings // not yet published
  )

//--- test projects - no artifacts, only used to test this repository

lazy val raceTestKitTest = createTestProject("race-testkit-test", testSettings).
  dependsOn(raceCore,raceTestKit)

lazy val raceCoreTest = createTestProject("race-core-test", testSettings).
  dependsOn(raceCore,raceTestKit)

lazy val raceNetJMSTest = createTestProject("race-net-jms-test", testSettings).
  dependsOn(raceNetJMS,raceTestKit).
  configs(MultiJvm).
  settings(
    mainClass in Compile := Some("gov.nasa.race.jms.JMSServer")
  ).
  addLibraryDependencies(logback,akkaSlf4j,akkaRemote)

lazy val raceNetKafkaTest = createTestProject("race-net-kafka-test", testSettings).
  enablePlugins(JavaAppPackaging).
  dependsOn(raceNetKafka,raceTestKit).
  configs(MultiJvm).
  settings(
    mainClass in Compile := Some("gov.nasa.race.kafka.KafkaServer")
  ).
  addLibraryDependencies(logback,zookeeper).
  addTestLibraryDependencies(logback)

lazy val raceNetDDSTest = createTestProject("race-net-dds-test", testSettings).
  enablePlugins(JavaAppPackaging).
  dependsOn(raceNetDDS,raceTestKit,raceAir).
  configs(MultiJvm).
  settings(
    mainClass in Compile := Some("gov.nasa.race.dds.DDSServer")
  )

lazy val raceAirTest = createTestProject("race-air-test", testSettings).
  dependsOn(raceAir,raceTestKit)


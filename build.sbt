// SBT build definition for RACE root project 

//--- imports from project
import RaceBuild._
import Dependencies._
import CommonRaceSettings._

shellPrompt in ThisBuild := { state => "[" + Project.extract(state).currentRef.project + "]> " }

lazy val commonSettings = commonRaceSettings ++
    Seq(
      organization := "gov.nasa",
      version := "1.3"
    )


//--- root project (only for aggregation)
lazy val root = createRootProject("race").
  aggregate(raceCore,raceNetJMS,raceNetKafka,raceNetDDS,raceSwing,raceWW,raceAir,raceWWAir,raceLauncher,
    raceTestKit,raceCoreTest,raceNetJMSTest,raceNetKafkaTest,raceAirTest).
  dependsOn(raceCore,raceNetJMS,raceNetKafka,raceNetDDS,raceSwing,raceWW,raceAir,raceWWAir,raceLauncher).
  enablePlugins(JavaAppPackaging,GitVersioning).
  settings(
    commonSettings,
    Defaults.itSettings,
    commands ++= LaikaCommands.commands,
    aggregate in MultiJvm := false,
    mainClass in Compile := Some("gov.nasa.race.main.ConsoleMain")
  )

//--- sub projects

// although this is not really a subproject we keep it here because we might override the uri
// locally (in local-race-build.properties) so that it can point to a local install.
// This saves us from having to do debug-related transient commits if WWJ and RACE are changed together
// NOTE - SBT 0.13 only supports git clone/checkout, i.e. to catch remote repo changes you have to do a "reload"
// (or delete the respective ~/.sbt/staging/<sha> before compiling/running)
lazy val wwjProject = RootProject(uri(sys.props.getOrElse("race.wwj_uri", "git://github.com/pcmehlitz/WorldWindJava.git#pcm")))


//--- those are the projects that produce build artifacts which can be used by 3rd party clients

lazy val raceCore = createProject("race-core", commonSettings).
  enablePlugins(JavaAppPackaging,GitVersioning).
  settings(
    mainClass in Compile := Some("gov.nasa.race.main.ConsoleMain")
  ).
  addLibraryDependencies(akkaActor,akkaRemote,typesafeConfig,nscalaTime,scalaReflect,jsch)

lazy val raceLauncher = createProject("race-launcher", commonSettings).
  dependsOn(raceCore).
  enablePlugins(JavaAppPackaging,GitVersioning).
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
  dependsOn(wwjProject).
  dependsOn(raceCore,raceSwing).
  addLibraryDependencies(akkaAll,scalaSwing)

lazy val raceWWAir = createProject("race-ww-air", commonSettings).
  dependsOn(raceWW,raceAir).
  addLibraryDependencies(typesafeConfig,nscalaTime)

lazy val raceTestKit = createProject("race-testkit", commonSettings).
  dependsOn(raceCore).
  addLibraryDependencies(defaultTestLibs,nscalaTime,akkaActor,akkaTestkit,akkaMultiNodeTestkit)

lazy val raceUI = createProject("race-ui", commonSettings).
  dependsOn(raceCore,raceSwing)

lazy val raceTools = createProject("race-tools", commonSettings).
  enablePlugins(JavaAppPackaging,GitVersioning).
  dependsOn(raceCore).
  settings(
    mainClass in Compile := Some("gov.nasa.race.tools.CryptConfig")).
  addLibraryDependencies(logback)

//--- test projects

lazy val raceTestKitTest = createTestProject("race-testkit-test", commonSettings).
  dependsOn(raceCore,raceTestKit)

lazy val raceCoreTest = createTestProject("race-core-test", commonSettings).
  dependsOn(raceCore,raceTestKit)

lazy val raceNetJMSTest = createTestProject("race-net-jms-test", commonSettings).
  dependsOn(raceNetJMS,raceTestKit).
  configs(MultiJvm).
  settings(
    mainClass in Compile := Some("gov.nasa.race.jms.JMSServer")).
  addLibraryDependencies(logback,akkaSlf4j,akkaRemote)

// NOTE - as of 11/19/2016 Kafka does not yet run under Scala 2.12, the tests have been disabled
lazy val raceNetKafkaTest = createTestProject("race-net-kafka-test", commonSettings).
  enablePlugins(JavaAppPackaging,GitVersioning).
  dependsOn(raceNetKafka,raceTestKit).
  configs(MultiJvm).
  settings(
    mainClass in Compile := Some("gov.nasa.race.kafka.KafkaServer")).
  addLibraryDependencies(logback,zookeeper).
  addTestLibraryDependencies(logback)

lazy val raceNetDDSTest = createTestProject("race-net-dds-test", commonSettings).
  enablePlugins(JavaAppPackaging,GitVersioning).
  dependsOn(raceNetDDS,raceTestKit,raceAir).
  configs(MultiJvm).
  settings(
    mainClass in Compile := Some("gov.nasa.race.dds.DDSServer"))

lazy val raceAirTest = createTestProject("race-air-test", commonSettings).
  dependsOn(raceAir,raceTestKit)


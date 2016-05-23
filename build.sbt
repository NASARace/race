// SBT build definition for RACE root project 

//--- imports from project
import RaceBuild._
import Dependencies._
import PluginSettings._
import TaskSettings._

shellPrompt in ThisBuild := { state => "[" + Project.extract(state).currentRef.project + "]> " }

lazy val commonSettings =
  pluginSettings ++
  taskSettings ++
  ReCov.taskSettings ++
  Assembly.taskSettings ++
  Seq(
    organization := "gov.nasa",
    version := "1.0",
    scalaVersion := "2.11.8",

    scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature"),
    resolvers ++= dependencyResolvers,

    publishArtifact in (Compile, packageDoc) := false,
    publishArtifact in (Compile, packageSrc) := false,

    fork in run := true,
    outputStrategy := Some(StdoutOutput),
    cleanFiles += baseDirectory.value / "tmp",
    Keys.connectInput in run := true,

    //coverageEnabled := true, // this should only be enabled for generating coverage reports
    ReCov.requirementSources ++= Seq( file("doc/specs/y1demo.md"),
                                      file("doc/specs/nonfunctional.md"))
  )


//--- root project (only for aggregation)
lazy val root = createRootProject("race").
  aggregate(raceCommon,raceData,raceCore,raceActors,raceTools,raceSwing,raceWW,swimServer,swimClient,kafkaServer,zkServer).
  dependsOn(raceCommon  % "test->test;compile->compile;multi-jvm->multi-jvm",
            raceCore,raceActors,raceTools,raceSwing,raceWW,swimServer,swimClient,kafkaServer,zkServer).
  configs(MultiJvm).
  enablePlugins(JavaAppPackaging,GitVersioning).
  settings(
    commonSettings,
    Defaults.itSettings,
    multiJVMSettings,
    aggregate in MultiJvm := false,
    mainClass in Compile := Some("gov.nasa.race.ConsoleMain")
  ).
  addLibraryDependencies(akkaSlf4j,akkaActor,akkaRemote,scopt,scalaArm).
  addTestLibraryDependencies(defaultTestLibs,akkaTestkit,akkaMultiNodeTestkit)

//--- sub projects

// we split these into sub-projects so that they can depend on each other - they
// can't depend on root since that would be cyclic. At some later point we might
// move common, the test-tools and the WorldWind viewer into their own toplevel
// projects, which will enable us to lift core and actors into root, but since that would
// require versions for dependency management we wait until this has stabilized and
// for now keep everything in one repo for convenience

// functions, types and values that are used by several artifacts
lazy val raceCommon = createProject("race-common").
  configs(MultiJvm).
  settings(commonSettings).
  addLibraryDependencies(typesafeConfig,nscalaTime,scalaXml,scalaReflect,jsch,scopt,scalaArm).
  addConfigLibraryDependencies("test,multi-jvm")(defaultTestLibs,akkaActor,akkaTestkit,akkaMultiNodeTestkit)

// common data types
lazy val raceData = createProject("race-data").
  dependsOn(raceCommon % "test->test;compile->compile").
  settings(commonSettings).
  addLibraryDependencies(defaultLibs,scodecAll,breeze).
  addTestLibraryDependencies(defaultTestLibs)

// lib artifact with core components 
lazy val raceCore = createProject("race-core").
  dependsOn(raceCommon % "test->test;compile->compile").
  settings(commonSettings).
  addLibraryDependencies(defaultLibs,akkaAll).
  addTestLibraryDependencies(defaultTestLibs,akkaActor,akkaTestkit)


// RACE specific actors
lazy val raceActors= createProject("race-actors").
  dependsOn(raceCommon % "test->test;compile->compile",
            raceCore % "test->test;compile->compile",
            raceData).
  settings(commonSettings).
  addLibraryDependencies(akkaAll,amqBroker,asyncHttp,kafka,akkaKafka,log4jOverSlf4j).
  addTestLibraryDependencies(defaultTestLibs,akkaActor,akkaTestkit)


// although this is not really a subproject we keep it here because we might override the uri
// locally (in local-race-buils.properties) so that it can point to a local install.
// This saves us from having to do debug-related transient commits if WWJ and RACE are changed together
// NOTE - SBT 0.13 only supports git clone/checkout, i.e. to catch remote repo changes you have to do a "reload"
// (or delete the respective ~/.sbt/staging/<sha> before compiling/running)
lazy val wwjProject = RootProject(uri(sys.props.getOrElse("race.wwj_uri", "git://github.com/pcmehlitz/WorldWindJava.git#pcm")))

// Java Swing extensions
lazy val raceSwing = createProject("race-swing").
  dependsOn(raceCommon).
  settings(commonSettings).
  addLibraryDependencies(akkaActor,scalaSwing,rsTextArea)


// our embedded WorldWind interface
lazy val raceWW = createProject("race-ww").
  dependsOn(wwjProject).
  dependsOn(raceCommon,raceData,raceCore,raceSwing).
  settings(commonSettings).
  addLibraryDependencies(akkaAll,scalaSwing,jsch)


lazy val raceTools = createProject("race-tools").
  dependsOn(raceCommon).
  enablePlugins(JavaAppPackaging).
  settings(
    commonSettings,
    mainClass in Compile := Some("gov.nasa.race.tools.CryptConfig")
  ).
  addLibraryDependencies(typesafeConfig,scopt,logback)


//--- test tools

lazy val swimServer = createProject("swimServer","test-tools/swim-server").
  dependsOn(raceCommon).
  enablePlugins(JavaAppPackaging).
  settings(
    commonSettings,
    mainClass in Compile := Some("gov.nasa.race.swimserver.MainSimple"),
    javaOptions in run += "-Xmx2G"   // because of embedded ActiveMQ broker
  ).
  addLibraryDependencies(logback,amqAll)


lazy val swimClient = createProject("swimClient", "test-tools/swim-client").
  dependsOn(raceCommon).
  enablePlugins(JavaAppPackaging).
  settings(
    commonSettings,
    mainClass in Compile := Some("gov.nasa.race.swimclient.MainSimple")
  ).
  addLibraryDependencies(logback,typesafeConfig,amqBroker,scopt)


lazy val zkServer = createProject("zkServer", "test-tools/zk-server").
  dependsOn(raceCommon).
  enablePlugins(JavaAppPackaging).
  settings(
    commonSettings,
    mainClass in Compile := Some("gov.nasa.race.zkserver.MainSimple")
  ).
  addLibraryDependencies(logback,zookeeper,scopt,log4jOverSlf4j)


lazy val kafkaServer = createProject("kafkaServer", "test-tools/kafka-server").
  dependsOn(raceCommon).
  enablePlugins(JavaAppPackaging).
  settings(
    commonSettings,
    mainClass in Compile := Some("gov.nasa.race.kafka.MainSimple")
  ).
  addLibraryDependencies(logback,kafka,scopt,log4jOverSlf4j)


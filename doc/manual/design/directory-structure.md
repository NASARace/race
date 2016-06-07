# Directory Structure
RACE is currently partitioned into a main project for the RACE executable itself,
5 subprojects containing RACE components that have separate build artifacts (jars
containing actors, data definitions etc.), and 2 auxiliary components that represent
external systems used during RACE demonstrations and testing.

Since each sub-project has its own package, their respective source directories
omit the full package path (e.g. `race-core/src/main/scala/` directly contains
files with classes that belong to package `gov.nasa.race.core`)

    bin/                  scripts to start RACE executables and tools
    config/               example RACE configuration files
    doc/                  RACE documentation sources
    src/                  sources for the RACE main application
    race-common/          sub-project with common utility library for other RACE modules
    race-data/            sub-project with RACE data definitions and utilities (e.g. FlightPos)
    race-core/            sub-project core RACE components such as Bus, Master and RaceActor base types
    race-actors/          sub-project containing RACE actors
    race-swing/           sub-project for common javax.swing wrappers and utilities
    race-ww/              sub-project for NASA WorldWind based viewer infrastructure
    race-tools/           sub-project with supportive tools (e.g. Config/file encryption)
    test-tools/           supporting external system mockups
        swim-server/      sub-project with JMS server to simulate a SWIM node
        swim-client/      sub-project with JMS client to simulate a SWIM client
        kafka-server/     sub-project with wrapper app to start a Kafka server
        zk-server/        sub-project with wrapper app to start a Zookeeper server

    build.sbt             the main SBT project definition file
    project/              SBT build system code

    target/               build artifacts of SBT and document generators (not archived,removed by clean command)
    tmp/                  runtime artifacts created by RACE executions (not archived)

Each (sub-) project follows a normal Maven layout, with the following structure

    <project-root>/src/
        main/             source artifacts for production code
            scala/        *.scala sources, possibly with package subdirecties
            java/         *.java sources, possibly with package subdirecties
            resources/    resources loaded through ClassLoaders
        test/             source artifacts for test codee
            scala/
            ...

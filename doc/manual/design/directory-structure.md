# Directory Structure
RACE is currently partitioned into a main project and a number of sub-projects that provide
the distributed build artifacts (jars). The main project is only used for aggregation, it does
not contain own code.

Sub-projects fall into three categories

  * exported artifacts (supposed to be used by external projects as library dependencies)
  * test projects (only used to test this repository, i.e. not used by 3rd party projects)
  * tools (stand-alone executables)
  
The purpose of this partitioning is to minimize (transitive) dependencies in 3rd party 
projects - they only need to add the RACE jars they need. This is especially useful with respect
to RACE networking components, which have a huge 3rd party dependency fan out as they are based
on large libraries such as Apache ActiveMQ.

Sub-project that are marked with a 'X' are exported as separately available jars.
  

    build.sbt               the main SBT project definition file
    race@                   link to script executing RACE with all exported sub-projects
                            
    race-core/           X  the basic RACE
                            
    race-net-jms/        X  JMS networking support for import/export, using Apache ActiveMQ
    race-net-dds/        X  DDS networking support, based on OMGs Java 5 PSM (interface-only jar)
    race-net-kafka/      X  Kafka networking support
    race-net-http/       X  Http import, using AsyncHttp
                            
    race-swing/          X  helper classes for Swing based user interfaces (style support etc.)
    race-ww/             X  basic infrastructure for embedding NASA WorldWind in RACE
                            
    race-launcher/       X  launching remote RACE instances
    race-ui/             X  RACE user interface (Swing console)
    race-testkit/        X  basic RACE test infrastructure (used by *-test projects)
                            
    race-air/            X  airspace related classes
    race-ww-air/         X  NASA WorldWind based airspace visualization
                            
    race-tools/             various tools used for RACE operation (en/decryption etc.)                            
                            
    race-core-test/         regression test project for race-core
    race-net-jms-test/      
    race-net-dds-test/                                  
    race-net-kafka-test/    
    race-air-test/          
                                
    config/                 example RACE configuration files
    doc/                    RACE documentation sources
                            
    project/                SBT build system code
    script/                 scripts to start RACE executables and tools
                            
    target/                 build artifacts of SBT and document generators (not archived,removed by clean command)
    tmp/                    runtime artifacts created by RACE executions (not under version control)

Each (sub-) project follows a normal Maven layout, using the following structure

    <project>/src/
        main/               source artifacts for production code
            scala/          *.scala sources, possibly with package subdirecties
            java/           *.java sources, possibly with package subdirecties
            resources/      resources loaded through ClassLoaders
        test/               unit test sources
            scala/          
            ...             
        multi-jvm/          [optional] integration test sources
            scala/
            ...

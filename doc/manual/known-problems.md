# Known Problems

## Build

### akka version conflict (09/08/17)
Without explicitly adding to `dependencyOverrides` in `build.sbt`, SBT complains about evicting the Akka 2.4.19
dependencies of `akka-http-core` and `akka-parsing` by the latest Akka versions. This is benign as they are compatible
at least up to Akka 2.5.4. See https://github.com/akka/akka-http/issues/1101

### SBT 1.x not yet usable (09/08/17)
Updating the SBT version in `project/build.properties` to >= 1.0 does still run into a number of un-resolvable plugins


## Runtime

### WorldWind server exceptions (09/08/17)
Running configurations that use the RaceViewerActor (which encapsulates NASA WorldWind) causes 

    SEVERE: Retrieval returned no content for https://worldwind20.arc.nasa.gov/mapcache...
    
log output during RACE startup. This is due to ongoing updates of the WorldWind server infrastructure and not RACE
related

### kafka-clients

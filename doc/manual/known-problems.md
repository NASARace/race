# Known Problems

## Build

### akka version conflict warning
During a clean build SBT complains about evicting older Akka versions (2.4.19) that are requested
by `akka-http` (more specifically, its transitive dependencies `akka-http-core` and `akka-parsing`).
This is benign as it just reflects that respective libraries have to be backwards compatible to the
old Akka (while being binary compatible with new versions). See
https://github.com/akka/akka-http/issues/1101

### SBT 1.x not yet used
The RACE build still cannot use SBT 1.x since the sbt-laika plugin (to generate documentation and
slides) is not yet available for the new SBT version.


## Runtime

### WorldWind server exceptions (09/08/17)
Running configurations that use the RaceViewerActor (which encapsulates NASA WorldWind) causes 

    SEVERE: Retrieval returned no content for https://worldwind20.arc.nasa.gov/mapcache...
    
log output during RACE startup. This is due to ongoing updates of the WorldWind server
infrastructure and not RACE related. However, running RACE configs that use the RaceViewerActor
on machines that did not previously cache WorldWind map tiles (by setting `cache-dir` in
the respective RaceViewerActor config) this can cause significant delays during rendering. Users
should make sure they have previously visited the geographic areas to show in demos with
caching turned on.

### kafka-clients
RACE still uses the 0.9 `kafka-clients` libraries (`ConfigurableKafkaConsumer` and
`ConfigurableKafkaProducer`) because it has to work with 0.9 `kafka` servers. The new 10.x
`kafka-clients` only work with new 10.x 'kafka' servers.

### KafkaServer
The `KafkaServer` is only intended to be a testing tool - it provides its own, embedded
`zookeeper` and a very minimal configuration which can cause unreliable timing behavior. Tests
(esp. `multi-jvm`) that use this tool should account for such timing by using delays.

### Windows console output
The standard Windows 10 console (command prompt) does not enable ANSI terminal sequences (color
and control), which is used by RACEs `ConsoleMain` driver (menus). As a workaround, use the
open sourced [ansicon](https://github.com/adoxa/ansicon) to enable ANSI sequences.

### RACE not starting because of RaceViewerActor timeout
On machines with slow graphics and network configurations that use a RaceViewerActor (WorldWind)
might not start because the actor does run into initialization timeout, especially if the map
cache is not populated. As a quick fix, start RACE with

    ./race -Drace.timeout=30 ...
    
If this succeeds, you can specifically set a `create-timeout` in the actor config of the
RaceActorViewer (each actor can be parameterized with `create-timeout`, `init-timeout` and
`start-timeout`, referring to respective actor lifetime phases)
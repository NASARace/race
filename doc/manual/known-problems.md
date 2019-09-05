# Known Problems

## problems with WorldWind/JOGL native libraries on Linux
Some Linux distributions have JDK packages that cause linker (ld.so) errors when loading
the native libraries required by WorldWind. So far this could be solved by installing
the newest JDK versions (>=11) directly from the [OpenJDK] website (JDK can be installed
in user directories).

## incompatible class file errors during RACE startup
While RACE could still work on Java 1.8, it uses a lot of 3rd party libraries (automatically
downloaded from Maven Central by SBT) which at this point have been built on newer Java versions
and hence cause incompatible class file errors during RACE load time. It is therefore recommended
to always use the latest stable Java/JDK release.

## kafka-clients
RACE still uses the 0.9 `kafka-clients` libraries (`ConfigurableKafkaConsumer` and
`ConfigurableKafkaProducer`) because it has to work with 0.9 `kafka` servers. The new 10.x
`kafka-clients` only work with new 10.x 'kafka' servers.

## KafkaServer
The `KafkaServer` is only intended to be a testing tool - it provides its own, embedded
`zookeeper` and a very minimal configuration which can cause unreliable timing behavior. Tests
(esp. `multi-jvm`) that use this tool should account for such timing by using delays.

## Windows console output
The standard Windows 10 console (command prompt) does not enable ANSI terminal sequences (color
and control), which is used by RACEs `ConsoleMain` driver (menus). As a workaround, use the
open sourced [ansicon](https://github.com/adoxa/ansicon) to enable ANSI sequences.

## RACE not starting because of RaceViewerActor timeout
On machines with slow graphics and network configurations that use a RaceViewerActor (WorldWind)
might not start because the actor does run into initialization timeout, especially if the map
cache is not populated. As a quick fix, start RACE with

    ./race -Drace.timeout=30 ...
    
If this succeeds, you can specifically set a `create-timeout` in the actor config of the
RaceActorViewer (each actor can be parameterized with `create-timeout`, `init-timeout` and
`start-timeout`, referring to respective actor lifetime phases)

[OpenJDK]: https://jdk.java.net

# Connecting RACE
there is life on the outside!

~

<a href="https://ti.arc.nasa.gov/profile/pcmehlitz/" rel="author">Peter.C.Mehlitz</a><br/>
SGT Inc, NASA Ames Research Center

## RACE Design
* everything is an actor
* (almost) every actor is configured
* actors communicate internally through (configured) bus channels
* **but how do they talk to the outside world?**

<img src="images/race-overview-2.svg" class="center scale55">


## Import/ExportActors
* dedicated import/export actors
* separation of protocol (actor: JMS,Kafka,..) and optional payload data handling (readers/writers)

<img src="images/import-export.svg" class="center scale65">


## RaceAdapter
* native lib + generic actor to connect external systems without network interface
* lightweight protocol with extensible/configurable payload data

<img src="images/race-adapter.svg" class="center scale75">


## Flightdeckz Example
* uses `SimpleTrackProtocol` (bi-directional track and proximity data)
* `race-server` process using `librace.a` to read/write shared memory segments

<img src="images/fdz-race-server.svg" class="center scale65">
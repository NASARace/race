# Connecting RACE

## Import/ExportActors
* dedicated import/export actors
* separation of protocol (actor: JMS,Kafka,..) and optional payload data handling (readers/writers)

<img src="../images/import-export.svg" class="center scale65">


## RaceAdapter
* native lib + generic actor to connect external systems without network interface
* lightweight protocol with extensible/configurable payload data

<img src="../images/race-adapter.svg" class="center scale75">


## Flightdeckz Example
* uses `SimpleTrackProtocol` (bi-directional track and proximity data)
* `race-server` process using `librace.a` to read/write shared memory segments

<img src="../images/fdz-race-server.svg" class="center scale65">
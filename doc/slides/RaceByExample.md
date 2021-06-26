# RACE by Example

website: <http://nasarace.github.io/race>  or <a href="../index.html">local</a><br/>
repository: <https://github.com/nasarace/race><br/>

<p class="author">
Peter.C.Mehlitz@nasa.gov<br/>
NASA Ames Research Center
</p>


## Slides
@:navigationTree { entries = [ { target = "#" } ] }

## Where did RACE come from?
* started as a distributed LVC simulation framework in 2015
  <img src="../images/lvc-sim.svg" class="center scale35">
  
* evolved into a general framework for event driven concurrent/distributed applications:

    + can import/export from/to external systems - **connectivity**
    + can process high event rate and data volume - **scalability**
    + supports distributed and massively concurrent operation
    + has batteries included (except Java runtime, SBT build system)


## Application Gamut
* common theme is parallel computation
* ranges from distributed/network applications to heterogeneous (GPU/SIMD) computation
* main focus is concurrent (multithreaded) program domain (without the pitfalls of thread sync)
* primary components are *Actors*

<img src="../images/parallel-computing.svg" class="center scale40">


## RACE Foundation: Actor Programming Model
* well known concurrency programming model since 1973 (Hewitt et al)
* _Actors_ are objects that communicate only through async messages
  ⟹ no shared state
* objects process messages one-at-a-time ⟹ sequential code

<img src="../images/actor.svg" class="center scale55">


## RACE Implementation: Actor System
* runs on JVM, programmed in Scala using Akka actor library
* RACE = set of communicating actors
* RACE messages are sent through (logical) publish/subscribe **channels**
* RACE actors/channels are runtime configured (JSON), not hardwired

<img src="../images/race-design.svg" class="center scale45">


## RACE Application Design
* uniform design - everything is an actor
* toplevel actors are deterministically created, initialized and terminated
  by _Master_ actor
* actors communicate through (configured) bus channels


<img src="../images/race-overview-2.svg" class="center scale55">


## Example 1: Data Diversity and Volumne
* live NAS visualization plus local sensors
* imports SWIM messages (SFDPS,TFM-DATA,TAIS,ASDE-X,ITWS) and local ADS-B
* up to 1000 msg/sec, 4500 simultaneous flights
* RaceViewerActor uses embedded NASA WorldWind for geospatial display

<div>
  <img src="../images/swim-sbs-all-ww.svg" class="left scale50">
  <img src="../images/race-nas.png" class="right scale50">
</div>
<kbd>console 1: ./race --vault ../conf.crypt config/air/swim-all-sbs-ww.conf</kbd>

## Example 2: (re)Play it Again
* only import actors are replaced with replay actors
* everything else stays the same

<img src="../images/source-swap.svg" class="center scale40">
<kbd>console 1: ./race -Darchive=../data/all-080717-1744 config/air/swim-all-sbs-replay-ww.conf</kbd>


## Example 3: Now With Remote Actors - Location Transparency
* actors are *location transparent* - can be moved to different RACE processes
* can exchange data- (SWIM) and control- messages (viewer sync)

<img src="../images/loc-trans.svg" class="center scale45">
<kbd>console 1: ./race config/remote-lookup/satellite1-replay.conf</kbd>
<kbd>console 2: ./race config/remote-lookup/satellite2-replay.conf</kbd>
<kbd>console 3: ./race  -Dmonitor.interval=15s -Darchive=../data/all-080717-1744/sfdps.ta.gz config/remote-lookup/master-replay-ww.conf</kbd>

## Example 4: What Data - SWIM Message Statistics
* RACE more than a data visualizer
* example collects live SWIM message statistics
* serves results as a web page (embedded webserver actor)

<div class="cmd">./race --vault ../conf.crypt config/air/swim-msg-stats.conf</div>
<div class="cmd"><a href="http://localhost:8080/race/statistics">output</a></div>

<img src="../images/swim-stats-config.svg" class="center scale20">


## Example 5: Is There a Problem with the Data?
* goal: find anomalies in flight update messages:
  <img src="../images/ts-anomalies.svg" class="alignTop scale35">

<div class="cmd">./race -Darchive=../data/all-080717-1744/tais.ta.gz config/air/swim-tais-stats-replay.conf</div>
<div class="cmd"><a href="http://localhost:8080/race/statistics">output</a></div>

<img src="../images/tais-stats-config.svg" class="left scale20">
<img src="../images/tais-stats-output.png" class="right scale30">


## Example 6: Properties can be more Complex - Parallel Approaches
goal: automatically detect parallel approaches that are angled-in exceeding
30° heading differences within given distance (causing loss of sight)

<div class="cmd">./race -Darchive=../data/nct-121918-161829/tais.ta.gz -Dstart-time=2018-12-19T16:32:20.000-08:00 config/air/swim-tais-papr-replay.conf</div>

<img src="../images/par-approach-config.svg" class="left scale20">
<img src="../images/par-approach-output.png" class="right scale50">


## Example 7: More Complex Properties - Trajectory Deviation
* how do positions for same flight differ between different input sources
  (ASDE-X, TAIS, SFDPS, direct ADS-B)?
* are differences random or systematic?

<div class="cmd">./race -Darchive=../data/ACA759-070717-min -Dstart-time=2017-07-08T06:54:30Z config/air/swim-trackdiff-replay.conf</div>

<img src="../images/trackdiff-config.svg" class="left scale25">
<img src="../images/trackdiff-output.png" class="right scale40">


## RACE as a Hub - Connecting Simulators
* heterogeneous system: combines live (SWIM) data and external simulators
* RACE used as a data hub that adds analysis (proximities, LoS detection)

<img src="../images/fdz-demo.svg" class="center scale65">

## Example 8: RACE as a Hub - Connecting Simulators
<img src="../images/fdz-config.svg" class="center scale40">

<img src="../images/fdz-output-a.png" class="left scale40">
<img src="../images/fdz-output-b.png" class="right scale40">


## Sharing Data Across Heterogeneous Organizations
* initial use case rapid rollout for disaster management
* applies to many situations where unified data view across organizational entities
  with intantionally isolated data is required

<img src="../images/share-problem.svg" class="center back scale70">


## Sharing Data - Overlay Network of RACE Nodes
* previous examples use a single RACE as data consumer/analyzer or hub
* can also be used to create networks of communicating RACE *nodes* (dedicated
  machines running RACE)

<img src="../images/share-adhoc.svg" class="center scale75">


## Sharind Data - RACE Node Functions
* node interfaces through 4 actor types: user-server, node-server, node-client, provider-import/export
* org-local data display and entry through user-server (browser interface)
* sync with upstream and downstream nodes through node-server/node-client
* upstream/downstream/user clients: JSON over websockets (allowing non-RACE endpoints)

<img src="../images/share-node-functions.svg" class="center scale60">


## Example 9: System for Hierarchical Ad hoc Reporting (SHARE)
* can be incorporated into any RACE application
* turn RACE applications into Web Application servers

<div class="cmd">./race config/net/share-coordinator.conf</div>
<div class="cmd"><a href="http://localhost:8080/share" target="_blank">link</a></div>
<div class="cmd">./race config/net/share-node_1.conf</div>
<div class="cmd"><a href="http://localhost:8081/share" target="_blank">link</a></div>
<div class="cmd">./race config/net/share-node_2.conf</div>
<div class="cmd"><a href="http://localhost:8082/share" target="_blank">link</a></div>

<img src="../images/share-browser.svg" class="left scale40">
<img src="../images/share-actors.svg" class="right up150 scale65">



## TL;DR
* RACE is an actor based framework for building distributed and concurrent applications
* primary design goals: scalability and extensibility
* can be used as a library (from external projects)
* used for runtime monitoring, trajectory analysis, simulation, dynamic data web application server,
  distributed applications,...
* open sourced under Apache v2
* written in Scala, based on Akka library
* ~700 files, ~100,000 ncloc
* developed since 2015
# Analyzing Airspace Data with RACE

website: <http://nasarace.github.io/race><br/>
repository: <https://github.com/nasarace/race><br/>

<p class="author">
{Peter.C.Mehlitz, Dimitra.Giannakopoulou, Nastaran.Shafiei}@nasa.gov<br/>
NASA Ames Research Center
</p>

## Slides
@:navigationTree { entries = [ { target = "#" } ] }

## What is RACE?
* started as a distributed LVC simulation framework in 2015
<img src="../images/lvc-sim.svg" class="center scale35">
* evolved into a general event driven application framework:

    + can import/export from/to external systems - **connectivity**
    + can process high event rate and data volume - **scalability**
    + supports distributed and massively concurrent operation
    + has batteries included (except Java runtime, SBT build system)


## RACE Implementation
* runs on JVM, programmed in Scala using Akka actor library
* basic building blocks are **actors** - objects that only communicate through messages (processed
sequentially)
* RACE messages are sent through **channels**
* RACE actors/channels runtime configured (JSON), not hardwired

<img src="../images/race-design.svg" class="center scale45">


## Presented Example Applications
automatic detection of

* (1) temporal inconsistencies in SWIM messages
* (2) unsafe parallel approach trajectories
* (3) trajectory deviations between different reporting systems
* (4) loss of separation between live and simulated aircraft


##  Common Tasks of Example Applications
<img src="../images/app-tasks.svg" class="center scale25">

mapped to dedicated actors

* import async data messages from external systems
* translate relevant external messages into data model entities
* filtering entities for relevance
* analyze relevant entities
* (opt) export entities to external systems
* report/visualize results


## (1) Detecting Temporal Inconsistencies in SWIM Messages
* goal: find anomalies in flight update messages:
<img src="../images/ts-anomalies.svg" class="alignTop scale35">
* RACE report:
<img src="../images/tais-stats-output.png" class="alignTop scale40">


## (1) Temporal Inconsistencies - Implementation & Lessons
<img src="../images/tais-stats-config.svg" class="center scale25">

* only ~140 app specific lines of code (mostly HTML formatting)
* original goal was only XML schema validation - time series analysis added
after visualization showed anomalies
* import layer convenient to switch between input sources
* first application to use HTML server for reporting (was running headless
in private network)


## (2) Detecting Unsafe Parallel Approach Trajectories
goal: automatically detect parallel approaches that are angled-in exceeding 
30° heading differences within given distance (causing loss of sight)

<img src="../images/par-approach-output.png" class="center scale65">


## (2) Parallel Approach - Implementation & Lessons
<img src="../images/par-approach-config.svg" class="center scale30">

* ~180 lines of code (approach analyzer)
* quadratic problem: pairwise trajectory comparison of un-synchronized irregular time series
* approach: filter candidates, detect parallel approach point, interpolate and compare traces backwards 
* filtering crucial (brute force parallel was limiting extensibility)
* uses generic trajectory infrastructure (traces, extrapolation/interpolation) - more needed


## (3) Detecting Deviations between Tracking Systems
* how do positions for same flight differ between different input sources
(ASDE-X, TAIS, SFDPS, direct ADS-B)?
* are differences random or systematic?

<img src="../images/trackdiff-output.png" class="center scale55">


## (3) Track Deviation - Implementation & Lessons
<img src="../images/trackdiff-config.svg" class="center scale40">

* ~180 lines of code (trajectory comparer)
* again motivated by visualization (noticing discrepancies)
* import layer convenient to switch between different input sources
* again pairwise trajectory analysis (selection by same flight id)
* comparison more expensive (interpolation, mean diff, variance for distance + angle)


## (4) Detecting Loss of Separation
* heterogeneous system: combines live (SWIM) data and external simulators
* RACE used as a data hub that adds analysis (proximities, LoS detection) 

<img src="../images/fdz-demo.svg" class="center scale65">


## (4) Loss of Separation - Output
* left shows TCAS display of simulator flight FDZ001 - live flight XY333 shown as alert
* right shows RACE viewer with LoS event between simulated (FDZ001) and live (XY333) flight

<div>
<img src="../images/fdz-output-a.png" class="left scale35">
<img src="../images/fdz-output-b.png" class="right scale35">
</div>


## (4) Loss of Separation - Implementation & Lessons
<img src="../images/fdz-config.svg" class="center scale45">

* uses generic native interface layer (memory layout, native library for external simulator)
* highly parallel problem (trajectory extrapolation, sorted proximity calculation)
* larger number of flights would require parallel computation 


## Conclusions
* actors are a good programming model for presented applications
* implementation platform (JVM, Scala, Akka) is suitable basis but favors throughput over latency
(soft realtime)
* trajectory analysis requires more domain specific support for interpolation, extrapolation
* visualization is important for property identification but currently a challenge 
(general: native APIs, specific: WorldWind)
* applications often highly parallel but HW based solution (GPU,SIMD) difficult in JVM 
without sacrificing extensibility
* native code/memory support becomes more important (hybrid systems interfacing, graphics APIs, 
heterogeneous computing)
* yet RACE already scales up to whole NAS (>4000 simultaneous flights, >1000 msg/sec) and more
optimizations in the queue 


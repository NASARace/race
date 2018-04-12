# RACE
Runtime for Airspace Concept Evaluation<br/> 
(in 10min)<br/>

website: <http://nasarace.github.io/race><br/>
external repo: <https://github.com/nasarace/race><br/>
internal repo: <https://babelfish.arc.nasa.gov/git/race>

~

<a href="https://ti.arc.nasa.gov/profile/pcmehlitz/" rel="author">Peter.C.Mehlitz</a><br/>
SGT Inc, NASA Ames Research Center


## What is RACE?
framework to build event driven applications that

* can import/export from/to external systems - **connectivity**
* can process high event rate and data volume - **scalability**
* have extensible set of concurrent, low overhead components
* support distributed and massively concurrent operation

<img src="../images/lvc-sim.svg" class="center scale40">


## Actors - Basic RACE Components
* well known concurrency programming model since 1973 (Hewitt et al)
* _Actors_ are objects that communicate only through async messages  
⟹ no shared state
* objects process messages one-at-a-time ⟹ sequential code

<img src="../images/actor.svg" class="center scale50">


## Actor Systems - Configuration
* RACE actor systems are JSON configured graphs

    + nodes are actors
    + edges are pub/sub (bus) channels through which actors communicate

<img src="../images/race-dataflow.svg" class="center scale40">


## RACE - Implementation
* _Master_ actor: initialization, supervision and termination of configured actors
* can model time
* local and global (network) bus

<img src="../images/race-overview-2.svg" class="center scale60">


## Example 1 - Live/Recorded NAS Visualization
* full NextGen SWIM (System Wide Information Management) import
* 500+ incoming  network msg/sec (mostly JMS)
* real time ADS-B import from local antenna
* visualization with NASA WorldWind

<img src="../images/swim-sbs-all-ww.svg" class="left scale50">
<img src="../images/race-viewer.svg" class="right scale45">
 

## Example 2 - SWIM Analysis
* used to analyze and verify SWIM data:

    + statistics (msg rate, volume, peaks)
    + XML validation
    + serve reports as HTML
    + detect track update anomalies (out-of-order,ambiguous,dropped,..)

<img src="../images/ts-anomaly-content.svg" class="left scale40">
<img src="../images/ts-anomaly-temp.svg" class="right scale40">

## Example 3 - LVC Simulation
* import live and (interactively) simulated tracks
* detect and send proximities to simulator
* detect NMACs of live tracks caused by simulator

<img src="../images/lvc-example.svg" class="center scale60">


## TL;DR
* RACE is a actor based application framework
* primary design goals: scalability and extensibility
* can be used as a library (from external projects)
* used for runtime monitoring, trajectory analysis, simulation, web-serving dynamic data,...
* open sourced under Apache v2
* written in Scala, heavily based on Akka library
* ~300 files, ~21000 ncloc
* developed since 2015


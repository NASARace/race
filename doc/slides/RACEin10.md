# RACE
scaling the world with actors

<https://github.com/nasarace/race>

~

<a href="https://ti.arc.nasa.gov/profile/pcmehlitz/" rel="author">Peter.C.Mehlitz</a><br/>
SGT Inc, NASA Ames Research Center

## Slides
@:toc root="#currentDocument".

## What is RACE for?
* event driven applications that connect to external systems
* processing of high data rate and volume
* distributed operation (local system interfacing, synchronized viewers, ...)

<img src="../images/lvc-sim.svg" class="center scale40">

## Show Me
* full NextGen SWIM (System Wide Information Management) import
  (200+ msg/sec)
* real time ADS-B import from local antenna
* visualization with NASA WorldWind

<img src="../images/swim-sbs-all-ww.svg" class="center scale60">
 
## How does it work 1 - Actors
* well known concurrency programming model since 1973 (Hewitt et al)
* _Actors_ are objects that communicate only through async messages  
⟹ no shared state
* objects process messages one-at-a-time ⟹ sequential code
* _Actors_ are the (extensible) components of RACE

<img src="../images/actor.svg" class="center scale55">

## How does it work 2 - Configuration
* actors are configured with JSON, not hardwired
* nodes are actors
* edges are channels through which actors communicate

<img src="../images/race-dataflow.svg" class="center scale55">

## How does it work 3 - RACE

<img src="../images/race-overview-2.svg" class="center scale60">

## Who is using RACE 

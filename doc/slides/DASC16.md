# RACE
building airspace simulations faster and better with actors

<https://github.com/nasarace/race>

## Slides
@:toc root="#currentDocument".

## Application Domain
* event driven LVC simulations
* interfaces to existing systems (live and simulators)
* can be used as stand-in for live systems
* distributed

<img src="images/lvc-sim.svg" class="center scale75">

## Main Challenges
uniform programming model for components supporting:

* scalability ("single aircraft to whole NAS")
* massively concurrent and distributed operation
* genericity
* platform independence ("can build and run on a laptop")

## Foundation: Actor Programming Model
* well known concurrency programming model since 1973 (Hewitt et el)
* objects that communicate only through async messages ⟹ no shared state
* process messages one-at-a-time ⟹ sequential code

<img src="images/actor.svg" class="center scale40">

## Actors implementation in RACE
* using **Akka** <http://akka.io>
* programmed in Scala (running on JVM)
* extended by `RaceActor` (configuration, state, initialization, remoting)
* uniform programming model for large (WWJ) *and* small components:

<pre>
   class ProbeActor (config: Config) extends SubscribingRaceActor {
     def handleMessage = {
       case BusEvent(channel,msg,sender) => println(msg)
     }
   }
</pre>

## RaceActors
* implements underlying state model in system code
* user code mostly just (user-) message handler
* supports local and network bus (publish subscribe)

<img src="images/actor-states.svg" class="center scale60">

## Actors don't live in a vacuum - RaceActorSystems
1. `RemoteMain` driver instantiating `RaceActorSystem` with config
2. `RaceActorSystem` instantiating `Master` actor
3. `Master` actor instantiating configured `RaceActors`

<img src="images/race-overview-2.svg" class="center scale55">

## Everything is configured
 * RACE is **not** a monolythic application - needs configuration as input
 * configs are text files with formal syntax (*HOCON*)

           universe {
             name = "mysimulation"
             ...
             actors = [
               { name = "aircraft"
                 class = "gov.nasa.race.actors.SimpleAircraft"
                 write-to = "/flightpos"
                 heading = 42
                 ...
               },
               { name = "probe"
                 class = ...
                 read-from = "

## Configuration is declarative DSL
configs define graphs:

* nodes are actors
* edges are channels through which actors communicate

<img src="images/race-dataflow.svg" class="center scale90">

## RaceActors/Systems support Location Transparency
* actors can be moved between processes
* no need to change actor code - just configuration

<img src="images/loc-trans.svg" class="center scale90">

## Data on Demand - ChannelTopics
* *ChannelTopics* are "valves" to turn on/off data flow along channel "pipes"
* fully transitive and async protocol for provider-lookup & registration

<img src="images/race-channeltopics.svg" class="center scale70">

## RaceViewer Visualization - WorldWind in an Actor
* adds thread-safe data acquisition (Layers) and UI framework (Panels)
* supports viewer synchronization through RACE channels

<img src="images/race-viewer.svg" class="center scale75">

## Demonstration Example
* full SWIM import (sfdps,tfmData,asde-x,itws)
* live ADS-B receiver import
* \> 4000 simultaneous flights
* \> 130 SWIM and ADS-B msg/sec
* ~9000 classes (~14000 loc)
* 200-500 MB heap memory
* < 10% CPU load on MacBook Pro (2.8GHz Intel i7, 16GB)

## Example Schematics
<img src="images/swim-sbs-all-ww.svg" class="center scale65">

## Lessons Learned
* actors are a suitable programming model for this domain
* rich research topic (patterns, verification)
* functional and object oriented programming works, but experienced users tend towards FP
* actor separation can be broken (global data in constructors and messages)
* location transparency offset by marshalling/unmarshalling costs
* back pressure should be integrated into Master failure management policy
* RACE is fun!
# SHARe

System for Hierarchical Ad hoc Reporting </br>

<p class="author">
Peter.C.Mehlitz@nasa.gov<br/>
NASA Ames Research Center
</p>

## Slides
@:toc root="#currentDocument".


## Problem
* task/incident related hierarchical reporting across heterogenous, isolated org entities (providers)
* rapid, non-disruptive rollout/tear down
* dynamic provider- and data sets
* fail-operational network
<img src="../images/share-problem.svg" class="up10 back center scale80">
  

## Key Requirements
* **non-intrusive**: no modification of provider machines/network, no end user installation
* **fail-operational**: no single point of failure causing data loss
* **self-repairing**: automatic data sync upon (re)connection
<img src="../images/share-failop.svg" class="center scale20">
* **flexible**: provider lists and data sets can be modified at runtime without disrupting operation
* **uniform**: can be used across several hierarchy levels
* **extensible**: optional provider specific automation


## Solution
* ad hoc overlay using dedicated, pre-configured *SHARE* nodes inside provider networks
<img src="../images/share-adhoc.svg" class="center scale80">


## Node Functions
* uniform node design
* server for provider-internal data display and entry (user interface)
* server for potential sub-nodes
* all function categories optional (configured)
* all external communication JSON over websockets (encrypted, host-/user-auth)
* all file formats JSON
<img src="../images/share-node-functions.svg" class="center up5 back scale65">
  

## Data Model and Flow
* distributed, replicated, filtered spreadsheet with typed rows (int,real,bool,string,intList,realList)
* columns owned by providers (write access)
* cells hold value and time
* owner + date resolve conflicts (CRDT)
* supports formulas (value- and time triggered)
* sync through connector upstream node
<img src="../images/share-flow.svg" class="center scale50">


## Detailed Data Model
<img src="../images/share-data.svg" class="center scale90">
  

## Formulas
* for time-and value- triggered cell value computation
* for intra- and inter-column properties
* simple s-expr over cell refs (glob expansion) and extensible function libraries

## Detailed Data Synchronization
<img src="../images/share-sync.svg" class="center scale90">
  

## Web Client
<img src="../images/share-browser.svg" class="center scale90">


## Provider Data Import / Export
* utilizing comprehensive RACE Import/Export actor infrastructure
* this is RACEs native domain (http, JMS, Kafka, sockets, ..., XML, Json, Protobuf, binary, ...)
* supports soft-realtime with > 1000 msg/sec
* configured actors that communicate via publish/subscribe channels
* imports processed as normal ColumnDataChange events
* can be gradually introduced/extended

<img src="../images/import-export.svg" class="left scale40">
<img src="../images/swim-sbs-all-ww.svg" class="right scale50">

  
## Actors
* SHARe is generic RACE application
* using actors as concurrent execution units
<img src="../images/share-actors.svg" class="center scale80">
  

## TBD
* demo
* NTP 
* runtime RL/CL swap
* user auth (pw change)
* more clients (Flutter)
* specs (variants)
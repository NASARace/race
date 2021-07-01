# SHARE

System for Hierarchical Ad hoc Reporting </br>

<p class="author">
Peter.C.Mehlitz@nasa.gov<br/>
NASA Ames Research Center
</p>

## Slides
@:navigationTree { entries = [ { target = "#" } ] }


## Problem
* task/incident related hierarchical reporting across heterogenous, isolated org entities (providers)
* rapid, non-disruptive rollout/tear down
* dynamic provider- and data sets
* fail-operational
<img src="../images/share-problem.svg" class="center up75 back scale70">
  

## Key Requirements
* **non-intrusive**: no modification of provider machines/network, no end user installation
* **fail-operational**: no single point of failure causing data loss
* **self-repairing**: automatic data sync upon (re)connection
<img src="../images/share-failop.svg" class="center scale20">
* **flexible**: provider lists and data sets can be modified at runtime without disrupting operation
* **uniform**: can be used across several hierarchy levels
* **extensible**: optional provider specific automation


## Solution
* ad hoc overlay using dedicated, pre-configured *SHARE nodes* inside provider networks
<img src="../images/share-adhoc.svg" class="center scale75">


## SHARE Node
* uniform node design (same SW, different config files)
* 4 optional functions: user-server, node-server, node-client, provider-import/export
* provider-local data display and entry through user-server (browser)
* sync with upstream and downstream through node-server/node-client
* upstream/downstream/user clients: JSON over websockets (allowing non-RACE/SHARE endpoints)
<img src="../images/share-node-functions.svg" class="center back scale55">
  

## Conceptual Data Model and Flow
* distributed, replicated, filtered spreadsheet with typed rows (int,real,bool,string,intList,realList)
* columns owned by providers (write access)
* cells hold value and time
* owner + date resolve conflicts (CRDT)
* supports formulas (value- and time triggered)
* sync through connector upstream node
<img src="../images/share-flow.svg" class="center scale50">


## SHARE Data Update
* configured (semi-) static structure: **ColumnList**, **RowList** 
* dynamic data: **ColumnData** (morphable key-value maps)
* change stimulus: **ColumnDataChange**, time-triggered formulas
* complete state snapshot: invariant **Node** object
<img src="../images/share-dm.svg" class="center scale70">


## Detailed Data Model
<img src="../images/share-data.svg" class="center scale90">
  

## Formulas
<img src="../images/share-formulas.svg" class="center scale90">


## Data Resynchronization Protocol
* based on column ownership (provider node) and ColumnData dates
* dates transmitted with *NodeState* message (per-CD for ext. columns, per-cell for own)
* newer own data transmitted with normal *ColumnDataChange* messages
* protocol can run in cycles until fixpoint is reached, i.e. does not need to halt local op
<img src="../images/share-sp.svg" class="center scale70">


## Detailed Data Resynchronization
<img src="../images/share-sync.svg" class="center scale80">
  

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
* demo / presentation slides
* NTP implementation for clock sync
* runtime RowList/ColumnList swap
* user auth (pw change)
* more clients (Flutter)
* specs (variants, Fret?)
* global monitoring (Mesa?)
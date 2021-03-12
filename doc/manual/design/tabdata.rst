TabData - A Reference Implementation for Robust Hierarchical Data Reporting
===========================================================================

system specification through

  - use case(s)
  - data model
  - computational model
  - system components
  - message protocols (JSON) between nodes and devices


Modeled as a tree of nodes, each node containing a *user server* for interactive data access within
a local network, upstream connector for parent node sync and a *node server* for child node sync.

Both server actors use websockets transmitting JSON to

  - support data push
  - minimize network configuration Requirements
  - allow for non-RACE clients

Tasks
-----
  - external data acquisition/entry
  - hierarchical data consolidation (horizontal/vertical node field eval)
  - sub-tree / parent data distribution (which columns / rows get reported/distributed)
  - data representation/visualization


Requirements
------------
  - disconnected sub-tree and rest of tree (upstream, peers) functional after upstream connection is lost
  - re-synchronization of data once sub-tree is reconnected to upstream
  - end-to-end connection loss detection and recovery (resync if upstream server process goes down)
  - (node hot swap tolerant)
  - minimal external network infrastructure required (only upstream node ip address is required)
  - minimize network traffic (push)
  - all network data is exchanged in standard (PE independent) format: Json
  - secure (encrypted protocols, sub-tree locality of node authorizations) 
  - conflict-free (state CRDT) -> hierarchy/location aware conflict resolution
  - schemas can be extended/swapped, data can be morphed at runtime
  - update of dependent cells via type-checked formulas
  - don't send out data that is not stored locally (reflects own data model)

motivating use case: ad hoc system integration - integrate as isolated node into
provider system with subsequent automation (if desired)


Data Model
----------

analogy is spreadsheet/table with hierarchical, typed rows and column-wise update/sync
row type system is provided as a parameter (rowList) so that it can be replaced/morphed/extended.
Data is kept in maps.

basic types (invariant so that they can be shared by concurrent processors):
  - Column (provider,record)
  - Row (field)
  - CellValue (val + change-date)
  - ColumnData : CD
  - ColumnDataChange : CDC
  - Node (id + lists + data + violated constraints)
  - NodeState : NS

  - CellRef
  - CellExpression (compiled formula) : CE
  - functionLibrary (list of valid CE functions)

configured (from JSON files):
  - ColumnList
  - RowList
  - formulas (row-type formulas: value- and time-triggered)
  - cellConstraints (boolean formulas: value- and time-triggered)

Each column is associated with one node (but each node can own several columns). Column data can only be updated from
that node or from upstream (goal setting). There should not be any row that is automatically (value triggered)
updated by both. Conflicting updates are resolved by update time stamp. There should not be any purely value
triggered update loop between column owner and upstream (-> implementation requirements).

Column and Row Ids are paths, which are potentially resolved through column/rowList or node Ids
in case they are relative. Reason for path keys (global addressing scheme) is that different row/colLists 
can reference the same logical entities/data
 
<TODO> Configs (lists) should support generic (node independent) specification to enable centralized update
questionable: this would either require a uniform view across peers or some non-intuitive syntax for shared config

Nodes can own several columns. Consequently, each column has a optional 'node' attribute to link to the main/node id
<TODO> what about nodes that only show/export columns other than the node name? Could be handled through 'attrs'

Formulas and constraints are kept separate from Column/RowList to enable per-node settings.

In general we try to share as much common config between nodes as possible, hence we factor config

<TODO> should CD be re-usable in different Column/RowLists ? If so they should not store either list id mandatorily
(shared CDs would need absolute Column/Row ids). Having relative CD ids that are resolved through list ids is not
the same as using absolute ids since lists might have their own namespaces (e.g. task specific groups of providers
collecting the same/overlapping data)

Update Semantics / Computational Model
--------------------------------------
  - updated one provider at a time (column)
  - first PDC values
  - then ordered formulas (can depend on other providers) that have changed dependencies (FV)
    or evaluation triggers (time)
  - publishing controlled by local sender config
  - acceptance of changes depends on local receiver config
  - if update/change events are reflected in the current node instance the new node has to be published first
    (current node should always show updates/changes in case the recipient needs to create summaries on demand)


CDC date is used to set high watermark, to detect races (t-cdc < t-last)

Each node passing a CDC up or down is responsible for checking if this is legit. We don't keep the
originator in the CDC since the receiver has no way to verify either the originator or if the data
has been altered by the sender (which can be valid in case of upstream formulas). We only know about
the directly connected nodes, which is immediate upstream and downstream.

Synchronization is based on a mixed operations and state based CRDT. The primary state is the cell/column
update time, which requires
  - time sync between nodes (-> RACE time sync via NTP)
  - a forced delay for own (eval) changes that exceeds maximum allowed clock skew (make sure that
    cause and effect time are guaranteed to be distinct)

The basis for operations based conflict resolution is that changes can only originate on the node that
owns the column, i.e. conflicts should show statically when looking at the config (ColumnList) files.

Sync between nodes is initiated by the child node sending a NodeState to the integrator upon start,
which includes all the ColumnData/date pairs it has. The integrator responds by sending the own
column/date pairs that have to be updated from the child node, followed by ColumnDataChange messages
that hold data which is outdated on the child.

The basis for conflict resolution is the cell timestamp value and the column owner. Child column/date
updates parent (since child nodes can still operate without parent connection). Peer/parent data from
the parent updates child (since the child could only have gotten these from the parent anyways). This
still needs to handle parent hotswap

All automated changes (import actors and time triggered formulas) are CDC generators, i.e. they just
inject CDCs into the update process.

<TODO> how to distinguish accidental feedback loops from intentional ones (valid: goal update from upstream causing
local change, causing upstream goal update). Purely value triggered formulas?

Security Concept
----------------
Node is dedicated server machine with restricted physical access and minimal service profile (attack angle). All data
is stored on the node server. The only node network facing access points are the tabdata URLs (no other data served).

All communication between nodes and user clients is using websockets over (encrypted) https

User client r/o access can be authenticated. Edit access is authenticated with per-user field access.


Refs
----
W. Edwards Deming: "without data you are just another person with an opinion"
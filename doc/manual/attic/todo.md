# RACE To Do List 

## short list

  * ssh driver (online config&cipher)

  * generic layer object selection (e.g. flight) - so far, ObjectInfoPanel is just a
    placeholder
    <viewer>

  * design docu - including graphics (svg) and slides (remark)

  * generic trajectory model - FlightPos Seqs are not efficient if we have lots of
  points, there needs to be a interface that can accommodate different implementation
  strategies
  <core>

  * database objects/actors - it is inefficient to let terminal actors accumulate
  flight infos (such as flight plans, trajectories, type info etc.). This should have
  a thread-safe query interface (to avoid in-process ask-patterns) and a message interface
  (inter-process use)
  <core>

  * generic DDS publisher/consumer actors - needs further investigation about VM
  compatibility/destabilization of existing DDS implementations (signal handler chaining
  is dangerous)
   <core>

  * FlightPosApproximator - sub-actor object to extrapolate FlightPos sequences if the
  underlying stream is not updated frequently enough (e.g. sfdps)
  <core>

  * pause/resume messages and handling
  <core>

  * configurable failure handling (critical/optional, restartable actors)

  * map furniture (center boundaries, airports etc.)
  <viewer>

  * SBS base station display layer (to show viewer capabilities beyond flightradar24)
  <viewer>


## long list

  * ProximityList in XPlaneActor, to efficiently handle realtime aircraft display
  in simulator cockpit view
  <viewer>

  * RaceConsole EnvironmentPage rework (layout, ease portmapping and sys property specs)
  <end-user UI>

  * RaceConsole config graph (visualize complex configurations)
  <end-user UI>

  * public key based config vault (user and/or machine, e.g. MAC based). Goal is to
  support externally supplied encrypted configs (i.e. values not readable by user)
  <core>

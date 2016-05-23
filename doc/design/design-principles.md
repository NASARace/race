RACE Design Principles
======================

## async initialization for remote actors
top level RACE actors can be instantiated without constructor arguments.
This is to enable pre-universe creation of remote actors, i.e. at at time when
the universe (bus, master) is not yet known. This principle is related to the
nonfunctional [R-100.3.1.1] deployment intepdendent actor requirement.

As a consequence, fields for remoting capable actors that are initialized from
master (universe) configuration data have to be vars so that they can be set
during async initialization. While vals for immutable data would be preferable, this
drawback is mitigated by
 
   * actor internals not being visible from the outside
   * RaceActor not dispatching via the concrete actor handleMessage() PF
     before the actor received and processed the initialization message
     
This leaves concrete actors with the constraint that they:

   * either have to provide meaningful default values for var fields
   * or make sure no code accessing those fields is called from their constructors
   * have to call super.initialize(..) from their overridden implementations, if they
     rely on base type functionality


[R-100.3.1.1]: requirements.md#R1.1_deployment_independent_actor
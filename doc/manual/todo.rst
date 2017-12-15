TODO List
=========

**OpenCL actor interface**
  use actor to manage OpenCL kernels/programs, in order to speed up parallel computations such
  as collision detection or position extrapolation

**discrete event protocol**
  implement discrete event protocol in trait that can be mixed into actor classes. This is to
  support "fast time" simulations inside of continuous time simulations

**adding completion flag to TrackedObject/FlightPos**
  some specialized events such as TATrack already have respective fields but FlightPos processors
  still require separate FlightCompleted messages

**resurrect and complete race-ui**
  RACE needs a GUI driver in addition to the textual ConsoleMain

**RaceActor parameterization interface**
  RACE configs are open, i.e. new parameters can be easily added without the need for updating any
  central registry. Unknown settings are silently ignored. While this is very flexible during
  development it also makes it harder to detect configuration errors and to create configuration tools.
  Add a RaceActor API to query/document supported configurations.
 
**create FlightGearActor**
  as an alternative to XPlaneActor

**create HLA import/export actors**
  could be used for FlightGear interfacing

**import weather data**
  such as wind
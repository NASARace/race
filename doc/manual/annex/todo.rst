TODO List
=========

**WorldWind alternative**
  NASA WorldWind Java is not an actively maintained project anymore. Since it is based on an old OpenGL version
  (fixed function pipeline, i.e. no shaders) and uses a 3rd party library with native components (JOGL) that has not
  been released on Maven Central in 6 years it becomes increasingly important to find alternatives (such as
  browser clients using [CesiumJS](https://cesium.com/platform/cesiumjs/). The current workaround of using
  https://github.com/pcmehlitz/WorldWindJava-pcm.git (which is published to Maven Central) should be considered
  temporary and should not be used as a basis for applications requiring substantial new geospatial viewer user
  interface components

**Scala 3**
  RACE still uses Scala 2.13.x. While it should be easy to port RACE sources (no use of macros) it first has to
  be evaluated if 3rd party RACE dependencies are compatible with/available for Scala 3

**OpenCL actor interface**
  race-cl is currently just a placeholder and needs an actor model. However, the use of OpenCL
  for heterogeneous computing support first has to be re-evaluated against Vulkan compute in terms of
  cross-vendor compatibility.

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

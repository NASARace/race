# Design Challenges

## programming environment

The current ODIN programming environments are Scala 2 on the server side and plain Javascript on the client (browser) side. While the latter one - for a browser based user interface - will not change, the server will see a transition into the Rust ecosystem. This becomes neccessary because of

(1) increasing dependencies on native 3rd party code (GDAL, Tensorflow, WindNinja, Elmfire, ...) - those would not only require JNI interface libraries in a JVM environment but also critically depend on distribution of required (platform/arch specific native) shared libraries, for which there is no standard Java mechanism. While some of the Java wrappers (e.g. geotrellis) might distribute (part of) the required native code this is certainly not true for fire specific systems such as WindNinja.

(2) increasing "version hell" for Java dependencies - if we are stuck with core 3rd party dependency versions (e.g. Akka) we run into "version hell" that forces us to override automatic dependency management, which in a Java world is likely to manifest only at runtime (transitive dependencies are just downloaded as jars and not recompiled from sources). 

This is aggravated by our problem with the changed Akka license. Either we stick to 2.6 / Scala 2 (which will run into increasing trouble since the community moves to Scala 3) or we have to switch to Akka 2.7+ / Scala 3, which will require to hide all Akka constructs behind own types purely for legal reasons. Not being able to guarantee stakeholders that they can develop ODIN apps without running into Akka licence fees is a show stopper.

RACE was trying to address the brittle-ness that comes with large recursive 3rd party dependency trees by using separate sub-modules that are built around core dependencies (race-core: Akka, race-space: orekit, ..) but the wildland fire domain is spanning almost all the big sub-trees.

(3) problem to create (binary) distributions - because of (1) and (2) it becomes increasingly difficult to install ODIN applications on stakeholder machines. As we move into production/field tests this is currently a major impediment that requires support resources we don't have.

The current set of reqired 3rd party jars for the ODIN demos includes 126 jars with 86MB. Just adding the native dependencies of WindNinja (mostly GDAL plus required shared libs) - for macOS alone - adds another 115 files with 158MB. This is a huge distribution problem. 

A related problem for binary distribution is the dependency on configuration files that depend on runtime type checks. For historical reasons (simulation prototypes) RACE was not assuming a hardcoded application model. All actors and their message interfaces are only checked at runtime (including the open actor parameter configuration). This makes resulting config files both too big (ODIN CZU demo example) and too brittle for production (typos in configs etc.). This was somewhat mitigated by increasingly depending on HOCON features (includes, value substitution etc.) that run counter to production code distribution. The problem is aggravated by the dependency on typesafe-config being one of the "version hell" candidates (see 2) 

(4) a significant amount of RACE code addresses mostly memory/speed bound limitations in the Java world (mostly the slice-based XML/JSON/CSV parsers). Some of these libraries (esp. JSON parser) need to have their APIs cleanded up/extended. This kind of work should be avoidable by using available 3rd party libraries (that are efficient enough). 

(5) structurally, large parts of ODIN functionality should (or even have to - see WindNinja) be moved into dedicated edge servers (small servers sitting between the original external data and an ODIN-node, providing only condensed info that hides the complexities of NetCDF, grib2 etc.). Using RACE for such edge servers is either impossible (because of native dependencies - e.g. dump1090) or too expensive (JVM/Akka overhead) 


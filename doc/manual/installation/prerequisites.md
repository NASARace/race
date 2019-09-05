# Prerequisites

## Hardware

RACE is a highly concurrent and potentially distributed system. Depending on your application
it can make use of multi-core CPUs if actors can work in parallel (such as translators or
stats collectors). CPU clock rate is usually less important - leave alone graphics, RACE rarely
exceeds CPU loads of 10% on >= 2GHz Intel CPUs.

Memory requirements for RACE also depend mostly on applications, or more specifically on data
sets such as large number of active flights. A full SWIM import (SFDPS, TAIS, ASDE-X with >=4500
live flights and 1-15 sec update intervals) can execute in <1.5 GB.

The most demanding component is graphics. If you plan to use the RaceViewerActor (WorldWind) for
geospatial display, machines should have >8GB of memory and a contemporary external GPU with 
at least 2GB of memory.

As a reference, most RACE applications (including full SWIM import) run fine on a 2017 MacBook Pro
(3.1GHz i7, 16GB, Radeon Pro 555).

## OS
As a JVM application RACE can run on OS X, Linux and Windows. Most development is done on OS X
machines.

## Software

### Java JDK
RACE needs to have a working Java JDK >=11 installed on developent machines,
preferably obtained from the [OpenJDK] website. You can check the current
java version by running

    > java -version

from the command line.

On OS X, you can check for installed JDKs by running

    > /usr/libexec/java_home -V

and setting `JAVA_HOME` in your `~/.profile` accordingly.

### SBT
The [Scala Build Tool][sbt] >=1.0 is used to build and run RACE. Please refer
to the [SBT documentation][sbt-install] for details of how to install SBT on
various platforms. The RACE build itself uses Scala >=2.13.0, which is installed
by SBT

### Git
To obtain and update RACE sources, we use [Git][git] as the distributed version
control system.


[OpenJDK]: https://jdk.java.net
[sbt]: http://www.scala-sbt.org/
[sbt-install]: http://www.scala-sbt.org/0.13/tutorial/index.html
[git]: https://git-scm.com/

# Prerequisites

## Java JDK
RACE needs to have a working Java JDK >=1.8 installed on developent machines,
which can be obtained from the [Oracle website][jdk]. You can check the current
java version by running

    > java -version

from the command line.

On OS X, you can check for installed JDKs by running

    > /usr/libexec/java_home -V

and setting `JAVA_HOME` in your `~/.profile` accordingly.

## SBT
The [Scala Build Tool][sbt] >=13.8 is used to build and run RACE. Please refer
to the [SBT documentation][sbt-install] for details of how to install SBT on
various platforms


## Git
To obtain and update RACE sources, we use [Git][git] as the distributed version
control system.


[jdk]: http://www.oracle.com/technetwork/java/javase/downloads/jdk8-downloads-2133151.html
[sbt]: http://www.scala-sbt.org/
[sbt-install]: http://www.scala-sbt.org/0.13/tutorial/index.html
[git]: https://git-scm.com/

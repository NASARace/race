# Prerequisites for RACE Installation

## 1. Java JDK
RACE needs to have a working Java JDK >=1.8 installed on developent machines,
which can be obtained from the [Oracle website][jdk]. You can check the current
java version by running

    > java -version

from the command line.

On OS X, you can check for installed JDKs by running

    > /usr/libexec/java_home -V

and setting `JAVA_HOME` in your `~/.profile` accordingly.

## 2. SBT
The [Scala Build Tool][sbt] >=13.8 is used to build and run RACE. Please refer
to the [SBT documentation][sbt-install] for details of how to install SBT on
various platforms


## 3. Git
To obtain and update RACE sources, we use [Git][git] as the distributed version
control system.


## 4. Gitbook
This is a optional component.
Although RACE documentation consists of [markdown][md] text files that can be
viewed by/edited in any editor, you can use the [Gitbook][gitbook-cli] document
generator to compile markdown into static html or pdf, or to start a local
webserver that enables viewing live documentation in a browser.

RACE uses a number of optional Gitbook plugins that need to be installed separately,
please refer to the [OS specific install instructions](osx.md) for
details.


[jdk]: http://www.oracle.com/technetwork/java/javase/downloads/jdk8-downloads-2133151.html
[sbt]: http://www.scala-sbt.org/
[sbt-install]: http://www.scala-sbt.org/0.13/tutorial/index.html
[git]: https://git-scm.com/
[md]: http://daringfireball.net/projects/markdown/syntax
[gitbook-cli]: http://github.com/GitbookIO/gitbook-cli
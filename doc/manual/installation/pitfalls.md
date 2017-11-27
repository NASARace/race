# Installation Pitfalls

## SBT
There are *SBT* packages available for most Linux distributions and OS X (e.g. via [homebrew][brew]
- please note *homebrew* can also be installed in the user file system). However, should no suitable
package be found *SBT* can also be installed manually since it is just a executable jar:

 1. download the SBT archive from http://www.scala-sbt.org/download.html
 2. expand at a suitable location
 3. add <sbt-dir>/bin to your `PATH` environment variable
 
On some older Linux distributions (e.g. RHEL6) it might be required to install *SBT* manually since
some of the available packages contain old SBT versions (<0.13) )that depend on (and install) older
Java JREs, which can create runtime conflicts with newer (>=1.8) JDK versions that were installed
separately.  
 
## Java - JRE instead of JDK
Please make sure to install a Java JDK (1.8.+) )instead of the JRE since `javac` is required to
compile some of the RACE sources. As of 11/20/2017 Scala is not yet fully compatible with JDK 1.9 

## SBT startup errors
Due to historical reasons, older RACE versions (<1.6) used a large number of SBT plugins that
might not be available for newer SBT versions and hence cause errors when starting up SBT.
The remedy is to use a newer RACE (>=1.6), which has been stripped of all plugin dependencies
that are not essential for normal (non-publishing) RACE builds.

[brew]: https://brew.sh/
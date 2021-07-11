Project Log
===========

**06/15/2021 - RACE v1.8 released on Github**
    After a long period of internal use this represents the first public update in a long time, not only
    bringing public RACE on-par with 3rd party dependencies such as JDK, Scala, Akka and many others. This also
    includes the new HttpServer based SHARE framework for robust hierarchical reporting

**12/31/2016 - RACE artifacts (jars) published on Maven Central**
    Build artifacts (jars) for various RACE modules are now available from Maven Central servers, which
    greatly simplifies to use RACE as a library from 3rd party projects. See `Using RACE from External Projects`_
    for details. A example project is available on https://github.com/NASARace/race-client-example.

**11/24/2016 - RACE v1.3 released** **12/31/2016 - RACE artifacts (jars) published on Maven Central**
    This update refactors RACE into sub-projects that minimize 3rd party dependencies based on specific
    application needs. For instance, there is no need to include Apache Kafka or ActiveMQ when the only
    data import is from ADS-B. The new structure will also be the basis for upcoming artifact publishing on
    Maven Central. Please see the `Directory Structure`_ and `Layers and Modules`_ sections for details.

**07/08/2016 - uploaded repository with example ADS-B data sets to Github**
    There is now a https://github.com/NASARace/race-data project on Github which contains several archived ADS-B
    data sets and respective config files to replay. Please note that the `Git Large File Extension`_ needs to be
    installed in order to retrieve the data.

**05/22/2016 - RACE sources are published on Github**
    This is the initial commit on https://github.com/NASARace/race. The RACE is on.


.. _Git Large File Extension: https://git-lfs.github.com/

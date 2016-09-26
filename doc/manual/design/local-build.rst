Local Build Configuration
=========================

Normally, the purpose of the build system is to automate the build so that it behaves the same on all machines.
Sometimes you need more control, to override standard build settings according to your environment. The main
reasons for such a deviation are

* using debug / development versions of 3rd party dependencies (e.g. WorldWind)
* components that separate publicly available interfaces from licensed implementations (e.g. DDS)

RACE supports this by means of two optional build configuration files in the RACE root directory:


local-race-build.properties
---------------------------
This file contains Java properties to specify settings such as URIs::

    race.wwj_uri=file:///home/me/worldwind

Those properties can then be referenced in ``build.sbt`` or any of the ``project/*.scala`` files::

    lazy val wwjProject = RootProject(uri(sys.props.getOrElse("race.wwj_uri", "git://github.com/pcmehlitz/WorldWindJava.git#pcm")))


local-build.sbt
---------------
This file can override SBT settings, and depends on using ``project/RaceBuild.scala`` provided methods to define projects
in ``build.sbt``, such as::

    lazy val raceCommon = createProject("race-common").
      ...
      settings(commonSettings).
      addLibraryDependencies(typesafeConfig,nscalaTime,scalaXml,scalaReflect,jsch,scopt,scalaArm).
      makeExtensible.  // <<< local extension point - settings from here on can be replaced in local-build.sbt
      addLibraryDependencies(omgDDS)

Note the ``makeExtensible`` call, which basically stores the respective SBT Project object for later retrieval and
modification from a ``local-build.sbt``::

    import RaceBuild._

    val raceCommon = extensibleProjects("race-common").settings(
      resolvers += "Local Maven Repository" at "file://"+Path.userHome.absolutePath+"/.m2/repository",
      libraryDependencies += "com.prismtech.cafe" % "cafe" % "2.2.2"
    )
    ...

Since ``Project`` instances are immutable (each of the calls in ``build.sbt`` creates a new instance) we can mark
any stage of the build.sbt project definition as the basis for local extension. This is important so that we can not
only add new libraries in our ``local-build.sbt`` but also replace the ones from ``build.sbt`` that follow the ``makeExtensible``
call.

Note this mechanism depends on SBT loading ``*.sbt`` files in alphabetical order.

This can basically override all settings of your locally extensible projects, and hence should be used with care. The
primary justification is to support proprietary 3rd party components that implement publicly available, open sourced
APIs (such as the OMGs Java 5 PSM for DDS) without the need to keep permanent branches in the repository.

Caveat
------
Note that **both files are not stored in the repository** (they are listed in ``.gitignore``). They only reside on the
local machine.

Code that is published or stored upstream should **not** depend on such settings. In general, it is better to use
repository branches for non-permanent specializations.
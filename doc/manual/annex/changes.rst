Changes
=======

**1.5.3**

  Introducing a change log (this file) to keep track of major changes. Entry basis is the RACE
  version as specified in build.sbt.

  Build configuration now uses explicit version numbers for external dependencies, to make sure builds
  behave the same regardless of external updates. Developers should frequently use the sbt-updates
  plugin to detect newer versions.

  All plugins that are not essential for normal builds (including documentation) have been
  removed from project/plugins.sbt, to minimize SBT update dependencies. Publishing and analysis
  plugins should be moved to respective SBT user config (~/.sbt/<version>).

  A new `race-adapter` sub-project has been added to support connecting to external systems
  that do not have a network interface. See `Connecting External Systems`_ for details.

  Other additions since 1.4.1 include the `StatsCollector` and `StatsReporter` infrastructure
  (see `Time Series Analysis`_, the `TrackTool` and Apache Avro based
  readers and writers. A new `track` package in `race-core` was introduced to unify and replace
  various different track types (such as the old `IdentifiableInFlightAircraft` and others).


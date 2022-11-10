// plugins used by RACE - note the strategy here is to use explicit versions as opposed to
// "latest.release" since we might need some of these to debug the build (e.g. dependencyTree), and
// if there are incompatible plugin changes or a plugin is no longer available SBT would not even
// load

// as a general rule we want to minimize the number of build-required plugins to minimize potential
// SBT upgrade problems on user/developer machines. This means we move plugins that are used for
// publishing and/or analyzing to the project global ~/.sbt. The pitfall with this approach is that
// we can't change settings of such plugins in RACE configuration. For this reason we still
// list those plugins here but comment them out

//--- essential test&build

// application / library packaging: https://github.com/sbt/sbt-native-packager
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.9.9")

// single executable (uber) jar assembly: https://github.com/sbt/sbt-assembly
//addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "1.2.0")

// document site generation with Laika: https://github.com/planet42/Laika
addSbtPlugin("org.planet42" % "laika-sbt" % "0.17.1")

// find latest versions of dependencies
addSbtPlugin("org.jmotor.sbt" % "sbt-dependency-updates" % "1.2.2")

//--- publishing support (needs to be in global on publishing machine)

// jar signing: https://github.com/sbt/sbt-pgp
//addSbtPlugin("com.jsuereth" % "sbt-pgp" % "2.1.2")

// publish artifacts to sonatype: https://github.com/xerial/sbt-sonatype
//addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "3.9.13")


//--- optional documentation (add to global SBT config at will)

// dependency analysis : https://github.com/jrudolph/sbt-dependency-graph
//addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.9.0")

// license reporter: https://github.com/sbt/sbt-license-report
// (no SBT 1.0 version as of 09/08/17)
//addSbtPlugin("com.typesafe.sbt" % "sbt-license-report" % "1.2.0")

// simple source statistics: https://github.com/orrsella/sbt-stats
//addSbtPlugin("com.orrsella" % "sbt-stats" % "1.0.7")

// create project dependency graph: https://github.com/dwijnand/sbt-project-graph
// (run projectsGraphDot which creates a target/projects-graph.dot)
//addSbtPlugin("com.dwijnand" % "sbt-project-graph" % "0.2.2")


//--- (optional) misc tools

// git commands from within sbt: https://github.com/sbt/sbt-git
//addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "0.8.5")

// static analysis: http://www.scalastyle.org/sbt.html
//addSbtPlugin("org.scalastyle" %% "scalastyle-sbt-plugin" % "latest.release" /*"1.0.0"*/)

// code formatting: https://github.com/sbt/sbt-scalariform
// NOTE - versions >= 1.5.0 are autoplugins that directly link into compile
//resolvers += Resolver.typesafeRepo("releases")
//addSbtPlugin("org.scalariform" % "sbt-scalariform" % "latest.release" /*"1.8.0"*/)

// eclipse config creation: https://github.com/typesafehub/sbteclipse
// (run as "eclipse skip-parents=false"):
//addSbtPlugin("com.typesafe.sbteclipse" % "sbteclipse-plugin" % "5.2.2")

// code coverage: https://github.com/scoverage/sbt-scoverage
//resolvers += Resolver.url("scoverage-bintray", url("https://dl.bintray.com/sksamuel/sbt-plugins/"))(Resolver.ivyStylePatterns)
//addSbtPlugin("org.scoverage" %% "sbt-scoverage" % "latest.release" /*"1.5.1"*/)

// check for upgraded dependencies: https://github.com/sksamuel/sbt-versions
// run 'checkVersions' from within SBT
// note this uses org.eclipse.aether.* which has DEBUG log output
//addSbtPlugin("com.sksamuel.sbt-versions" % "sbt-versions" % "0.2.0")



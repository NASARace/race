// plugins used by RACE - note the strategy here is to use explicit versions
// as opposed to "latest.release" since we might need some of these to debug
// the build (e.g. dependencyTree)

// NOTE - "latest.release" does not work for most plugins that are SBT version dependent
// as they don't publish releases

//--- essential test&build

// application / library packaging: https://github.com/sbt/sbt-native-packager
addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.0.6")

// single executable (uber) jar assembly: https://github.com/sbt/sbt-assembly
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.3")

// multi-jvm testing: https://github.com/sbt/sbt-multi-jvm
addSbtPlugin("com.typesafe.sbt" % "sbt-multi-jvm" % "0.3.11")

// jar signing: https://github.com/sbt/sbt-pgp
addSbtPlugin("com.jsuereth" % "sbt-pgp" % "1.0.0")


//--- (optional) tools

// git commands from within sbt: https://github.com/sbt/sbt-git
addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "0.8.5")

// static analysis: http://www.scalastyle.org/sbt.html
addSbtPlugin("org.scalastyle" %% "scalastyle-sbt-plugin" % "0.7.0")

// code formatting: https://github.com/sbt/sbt-scalariform
// NOTE - versions >= 1.5.0 are autoplugins that directly link into compile
resolvers += Resolver.typesafeRepo("releases")
addSbtPlugin("org.scalariform" % "sbt-scalariform" % "1.4.0")

// dependency analysis : https://github.com/jrudolph/sbt-dependency-graph
addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "latest.release" /*"0.8.1"*/)

// license reporter: https://github.com/sbt/sbt-license-report
addSbtPlugin("com.typesafe.sbt" % "sbt-license-report" % "1.2.0")

// simple source statistics: https://github.com/orrsella/sbt-stats
addSbtPlugin("com.orrsella" % "sbt-stats" % "latest.release" /*"1.0.5"*/)

// eclipse config creation(run as "eclipse skip-parents=false"):
addSbtPlugin("com.typesafe.sbteclipse" % "sbteclipse-plugin" % "4.0.0")

// code coverage: https://github.com/scoverage/sbt-scoverage
resolvers += Resolver.url("scoverage-bintray", url("https://dl.bintray.com/sksamuel/sbt-plugins/"))(Resolver.ivyStylePatterns)
addSbtPlugin("org.scoverage" %% "sbt-scoverage" % "1.3.3")

// static analysis: https://github.com/sksamuel/sbt-scapegoat
addSbtPlugin("com.sksamuel.scapegoat" %% "sbt-scapegoat" % "1.0.4")

// create project dependency graph: https://github.com/dwijnand/sbt-project-graph
// (run projectsGraphDot which creates a target/projects-graph.dot)
// does not work with root projects ('wwjRoot' from build.sbt of 'wwjProject')
//addSbtPlugin("com.dwijnand.sbtprojectgraph" % "sbt-project-graph" % "0.1.0")

// check for upgraded dependencies: https://github.com/sksamuel/sbt-versions
// run 'checkVersions' from within SBT
// note this uses org.eclipse.aether.* which has DEBUG log output
//addSbtPlugin("com.sksamuel.sbt-versions" % "sbt-versions" % "0.2.0")

// run shell commands from within SBT: https://github.com/melezov/xsbt-sh
// (README states version "0.1.0" but there is none on the resolver)
// it's tiny and very generic so we just add the command locally (ShellCmd.scala) to avoid another resolver
//resolvers += "Element Releases" at "http://repo.element.hr/nexus/content/repositories/releases/"
//addSbtPlugin("hr.element.xsbt" % "xsbt-sh" % "0.0.2")

//--- not published tools

// verify downloaded dependencies: https://github.com/JosephEarl/sbt-verify
// (needs to be cloned and locally published)
//addSbtPlugin("uk.co.josephearl" % "sbt-verify" % "0.2.0")

// static analysis: https://github.com/scala/scala-abide
// (http://www.slideshare.net/iuliandragos/scala-abide-a-lint-tool-for-scala)
/**
val abideVersion = "latest.release"
addSbtPlugin("com.typesafe" % "sbt-abide" % abideVersion)
libraryDependencies ++= Seq(
  "com.typesafe" %% "abide-core" % abideVersion,
  "com.typesafe" %% "abide-extra" % abideVersion,
  "com.typesafe" %% "abide-akka" % abideVersion
)
**/

libraryDependencies += "ch.qos.logback" % "logback-classic" % "latest.release"

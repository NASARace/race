import sbt.Keys._
import sbt.{Keys, StdoutOutput, _}

/**
  * this is where we aggregate common settings for RACE projects
  * Used in RaceBuild for project initialization
  */
object CommonRaceSettings {

  val scalaVer = "2.13.1" // keep it as a ordinary var for libs that are Scala version dependent (e.g. scala-reflect)

  lazy val commonRaceSettings =
      PluginSettings.pluginSettings ++
      TaskSettings.taskSettings ++
      Seq(
        scalaVersion := scalaVer,
        scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature"
          , "-target:9"
          //, "-opt-warnings"
          , "-opt:l:method"   // enables all intra-method optimizations
          , "-opt:l:inline"               // use only for local inlining or we break incremental compilation
          , "-opt-inline-from:<sources>"  //  - " -
        ),
        resolvers ++= Dependencies.dependencyResolvers,

        fork in run := true,
        outputStrategy := Some(StdoutOutput),
        cleanFiles += baseDirectory.value / "tmp",
        Keys.connectInput in run := true
      )

  //import com.typesafe.sbt.pgp.PgpKeys.{publishLocalSigned, publishSigned}  // requires sbt-pgp plugin

  lazy val noPublishSettings = Seq(
    publishArtifact := false,

    // those should not be required if publishArtifact is false,
    // but without it we still get Ivys/ and poms/ during publishing
    publish := {},
    publishLocal := {},


    // we can't add publishSigned and publishLocalSigned here to avoid created Ivys/ and poms/
    // files since this would require sgt-pgp to be imported, which is only the case on publishing machines
    skip in publish := true
  )
}

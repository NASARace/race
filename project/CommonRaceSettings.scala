import com.typesafe.sbt.pgp.PgpKeys.{publishLocalSigned, publishSigned}  // requires sbt-pgp plugin
import sbt.Keys._
import sbt.{Keys, StdoutOutput, _}

/**
  * this is where we aggregate common settings for RACE projects
  * Used in RaceBuild for project initialization
  */
object CommonRaceSettings {

  val scalaVer = "2.12.4" // keep it as a ordinary var for libs that are Scala version dependent (e.g. scala-reflect)

  lazy val commonRaceSettings =
      PluginSettings.pluginSettings ++
      TaskSettings.taskSettings ++
      Seq(
        scalaVersion := "2.12.3",
        scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature", "-opt-inline-from:**"), // alternatively use -opt-inline-from:<source> for compilation scope
        resolvers ++= Dependencies.dependencyResolvers,

        fork in run := true,
        outputStrategy := Some(StdoutOutput),
        cleanFiles += baseDirectory.value / "tmp",
        Keys.connectInput in run := true
      )

  lazy val noPublishSettings = Seq(
    publishArtifact := false,

    // those should not be required if publishArtifact is false, but at least publishLocalSigned still produces Ivys/ and poms/
    publish := {},
    publishLocal := {},
    publishSigned := {},
    publishLocalSigned := {} // should not be required but otherwise we get Ivys/ and poms/
  )
}

import sbt.Keys._
import sbt.{Keys, StdoutOutput, _}

/**
  * this is where we aggregate common settings for RACE projects
  * Used in RaceBuild for project initialization
  */
object CommonRaceSettings {

  lazy val commonRaceSettings =
      PluginSettings.pluginSettings ++
      TaskSettings.taskSettings ++
      Assembly.taskSettings ++
      Seq(
        scalaVersion := "2.12.1",
        scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature"),
        resolvers ++= Dependencies.dependencyResolvers,
        publishArtifact in (Compile, packageDoc) := false,
        publishArtifact in (Compile, packageSrc) := false,

        fork in run := true,
        outputStrategy := Some(StdoutOutput),
        cleanFiles += baseDirectory.value / "tmp",
        Keys.connectInput in run := true
      )

}

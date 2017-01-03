import com.typesafe.sbt.pgp.PgpKeys.{publishLocalSigned, publishSigned}
import sbt.Keys._
import sbt.{Keys, StdoutOutput, _}

/**
  * this is where we aggregate common settings for RACE projects
  * Used in RaceBuild for project initialization
  */
object CommonRaceSettings {

  val gitRev = settingKey[String]("retrieve git rev-list count")

  lazy val commonRaceSettings =
      PluginSettings.pluginSettings ++
      TaskSettings.taskSettings ++
      Assembly.taskSettings ++
      Seq(
        scalaVersion := "2.12.1",
        scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature"),
        resolvers ++= Dependencies.dependencyResolvers,

        gitRev := Process("git rev-list --count HEAD", baseDirectory.value).lines.head,

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

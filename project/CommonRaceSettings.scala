import sbt.Keys._
import sbt.{Keys, StdoutOutput, _}

/**
  * this is where we aggregate common settings for RACE projects
  * Used in RaceBuild for project initialization
  */
object CommonRaceSettings {

  val scalaVer = "2.13.10" // keep it as a ordinary var for libs that are Scala version dependent (e.g. scala-reflect)
  val org = "gov.nasa.race"

  lazy val commonRaceSettings = {
      PluginSettings.pluginSettings ++
      TaskSettings.taskSettings ++
      Seq(
        scalaVersion := scalaVer,
        scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature"
          // , "-target:9"
          //, "-opt-warnings"
/*
          , "-opt:l:method"               // enables all intra-method optimizations
          , "-opt:l:inline"
          // NOTE - apart from 'sources' this should be used sparingly since it (transitively) breaks incremental compilation
          , "-opt-inline-from:<sources>"
          , "-opt-inline-from:gov.nasa.race.common.Slice"
          , "-opt-inline-from:gov.nasa.race.common.RangeStack"
          , "-opt-inline-from:gov.nasa.race.common.RichDouble"
          , "-opt-inline-from:gov.nasa.race.geo.LatLon"
          , "-opt-inline-from:gov.nasa.race.uom.Angle"
          , "-opt-inline-from:gov.nasa.race.uom.Length"
*/
        ),
        resolvers ++= Dependencies.dependencyResolvers,

        run / fork := true,

        // TODO - get rid of these once we fully moved from WorldWind (in-process) to Cesium (browser) UIs
        run / javaOptions ++= Seq(
          "--add-opens", "java.desktop/sun.awt=ALL-UNNAMED",
          "--add-opens", "java.desktop/com.apple.eawt=ALL-UNNAMED"
        ),

        Test / fork := true,
        outputStrategy := Some(StdoutOutput),
        cleanFiles += baseDirectory.value / "tmp",
        run / Keys.connectInput := true,

        organization := org
      )
  }

  //import com.typesafe.sbt.pgp.PgpKeys.{publishLocalSigned, publishSigned}  // requires sbt-pgp plugin

  lazy val noPublishSettings = Seq(
    publishArtifact := false,

    // those should not be required if publishArtifact is false,
    // but without it we still get Ivys/ and poms/ during publishing
    publish := {},
    publishLocal := {},


    // we can't add publishSigned and publishLocalSigned here to avoid created Ivys/ and poms/
    // files since this would require sgt-pgp to be imported, which is only the case on publishing machines
    publish / skip := true
  )
}

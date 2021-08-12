import sbt._
import Keys._

object SonatypeSettings {

  val sonatypeSettings = Seq(

    Global / pomExtra := {
      <url>http://nasarace.github.io/race/</url>
        <licenses>
          <license>
            <name>Apache 2</name>
            <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
            <distribution>repo</distribution>
          </license>
        </licenses>
        <scm>
          <url>https://github.com/NASARace/race.git</url>
          <connection>scm:git:github.com/NASARace/race.git</connection>
        </scm>
        <developers>
          <developer>
            <id>pcmehlitz</id>
            <name>Peter Mehlitz</name>
            <url>https://ti.arc.nasa.gov/profile/pcmehlitz</url>
          </developer>
        </developers>
    }
  )
}

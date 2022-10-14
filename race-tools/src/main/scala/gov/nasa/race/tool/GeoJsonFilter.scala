package gov.nasa.race.tool

import gov.nasa.race.geo.{GeoJsonFilterStream, GeoPosition}
import gov.nasa.race.main.CliArgs
import gov.nasa.race.uom.{DateTime, Time}
import gov.nasa.race.util.{GzInputStream, ThreadUtils}

import java.io.{File, FileInputStream, FileOutputStream, PrintStream}
import scala.concurrent.duration.DurationInt

/**
 * command line tool to filter GeoJSON files for objects within WSEN rectangle specified in degrees
 */
object GeoJsonFilter {

  class Opts extends CliArgs("usage:") {
    var inFile: Option[File] = None // the text archive file to extract from
    var outFile: Option[File] = None // optional file to store matches in
    var west: Double = Double.NaN
    var south: Double = Double.NaN
    var east: Double = Double.NaN
    var north: Double = Double.NaN

    opt1("-o", "--out")("<pathName>", s"optional pathname of file to store matching GeoJSON objects (default = $outFile)") { pn =>
      outFile = Some(new File(pn))
    }

    requiredOpt1("-w", "--west")("<degrees>", "West boundary of rectangular filter in degrees") { v=> west = v.toDouble }
    requiredOpt1("-s", "--south")("<degrees>", "South boundary of rectangular filter in degrees") { v=> south = v.toDouble }
    requiredOpt1("-e", "--east")("<degrees>", "East boundary of rectangular filter in degrees") { v=> east = v.toDouble }
    requiredOpt1("-n", "--north")("<degrees>", "North boundary of rectangular filter in degrees") { v=> north = v.toDouble }

    requiredArg1("<pathName>", "GeoJSON file to filter") { a =>
      inFile = parseExistingFileOption(a)
    }
  }
  val ClearLine   = "\u001b[1K\u001b[0G"
  var opts = new Opts()

  def main (args: Array[String]): Unit = {
    if (opts.parse(args)) {
      for (file <- opts.inFile) {
        val ps = opts.outFile match {
          case Some(oFile) => new PrintStream(new FileOutputStream(oFile))
          case None => System.out
        }
        val outputToFile = ps ne System.out

        val fis = new FileInputStream(file)
        val is = if (file.getName.endsWith(".gz")) new GzInputStream(fis) else fis

        val west = opts.west
        val south = opts.south
        val east = opts.east
        val north = opts.north

        var nOut = 0
        var done = false
        var monitor: Option[Thread] = None

        def filter (pos: GeoPosition): Boolean = pos.latDeg >= south && pos.latDeg <= north && pos.lonDeg >= west && pos.lonDeg <= east
        val gjs = new GeoJsonFilterStream(is, filter)
        val fileLength = file.length()

        if (outputToFile) {
          println(s"reading $fileLength bytes from $file")
          println(s"writing to ${opts.outFile.get}")
          print("filtering...")

          val t = new Thread {
            val nTotal = file.length()

            setDaemon(true)

            override def run(): Unit = {
              val t0 = DateTime.now

              while (!done) {
                val nRemaining = fis.available()
                val nRead = nTotal - nRemaining
                val ratePerMin = nRead / DateTime.now.timeSince(t0).toMinutes
                val minRemaining = (nRemaining / ratePerMin).round

                print(s"$ClearLine$nOut bytes written ($nRemaining bytes / ${minRemaining}min remaining)...")
                ThreadUtils.sleepInterruptible(5.seconds)
              }

              println(s"${ClearLine}done.")
            }
          }
          t.start()
          monitor = Some(t)
        }

        var b: Int = gjs.read()
        while (b != -1) {
          ps.write(b.toChar)
          b = gjs.read()
          nOut += 1
        }
        done = true

        if (outputToFile) {
          monitor.foreach( _.interrupt())
          ps.close()
        }
        gjs.close()
      }
    }
  }
}

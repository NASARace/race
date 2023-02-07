/*
 * Copyright (c) 2022, United States Government, as represented by the
 * Administrator of the National Aeronautics and Space Administration.
 * All rights reserved.
 *
 * The RACE - Runtime for Airspace Concept Evaluation platform is licensed
 * under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package gov.nasa.race.space

import gov.nasa.race.common.{MutXyz, squareRoot, squared}
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.core.RaceActor
import gov.nasa.race.geo.{Datum, GeoPosition, GreatCircle}
import gov.nasa.race.{Dated, DatedOrdering, ifSome, ifTrue, ifTrueCheck}
import gov.nasa.race.trajectory.{MutAccurateTrajectory, Trajectory}
import gov.nasa.race.uom.Angle.{Acos, Radians, Sin, π_2}
import gov.nasa.race.uom.DateTime.UndefinedDateTime
import gov.nasa.race.uom.Length.{Kilometers, Meters}
import gov.nasa.race.uom.Time.{Seconds, UndefinedTime}
import gov.nasa.race.uom.{Angle, DateTime, Length, Time}
import gov.nasa.race.util.FileUtils
import org.hipparchus.geometry.euclidean.threed.Vector3D
import org.hipparchus.ode.events.Action
import org.hipparchus.util.FastMath
import org.orekit.bodies.{GeodeticPoint, OneAxisEllipsoid}
import org.orekit.data.{ClasspathCrawler, DataContext, DirectoryCrawler, ZipJarCrawler}
import org.orekit.frames.{FactoryManagedFrame, Frame, FramesFactory, TopocentricFrame}
import org.orekit.propagation.{AbstractPropagator, SpacecraftState}
import org.orekit.propagation.analytical.tle.{TLEPropagator, TLE => OreTLE}
import org.orekit.propagation.events.{ElevationExtremumDetector, EventsLogger}
import org.orekit.propagation.events.handlers.EventHandler
import org.orekit.time.{AbsoluteDate, TimeScalesFactory, UTCScale}
import org.orekit.utils.{Constants, IERSConventions, PVCoordinates}

import java.io.File
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable.ArrayBuffer
import scala.language.implicitConversions

/**
 * OreKit library facade object (we might have several clients)
 *
 * TODO - break up into shared part in companion and per-client instances that can be separately configured (LogWriters etc.)
 */
object OreKit {

  //--- initialization (ephemeris)

  private var _hasData = false

  def hasData: Boolean = _hasData

  def ensureData(): Unit = synchronized {
    if (!checkData()) {
      throw new RuntimeException("failed to locate OreKit data")
    }
  }

  def checkData(): Boolean = synchronized {
    _hasData || loadPropertyData() || loadEnvData() || loadClasspathData() || loadPathData()
  }

  def loadClasspathData(): Boolean = synchronized {
    val resourceUrl: URL = getClass.getResource("orekit-data-master.zip")
    ifTrueCheck (resourceUrl != null) {
      val manager = DataContext.getDefault.getDataProvidersManager
      manager.addProvider(new ZipJarCrawler( resourceUrl)) // BEWARE - this might be outdated
      _hasData = true
      true
    }
  }

  def loadPathData(): Boolean = {
    Seq("orekit-data", "orekit-data.jar", "orekit-data.zip").exists(p => {
      FileUtils.findInPathHierarchy(p).map(loadFSData).isDefined
    })
  }

  def loadFSData (f: File): Boolean = {
    if (f.isFile) {
      FileUtils.hasAnyExtension(f, "zip", "jar") && loadArchiveData(f)
    } else if (f.isDirectory) {
      loadDirData(f)
    } else false // not found or no known file type
  }
  def loadFSData (path: String): Boolean = loadFSData(new File(path))

  def loadEnvData (): Boolean = {
    val env = System.getenv("OREKIT_DATA")
    (env != null) && loadFSData(new File(env))
  }

  def loadPropertyData (): Boolean = {
    val prop = System.getProperty("orekit.data")
    (prop != null) && loadFSData(new File(prop))
  }

  def loadDirData(dataDir: File): Boolean = synchronized {
    if (dataDir.isDirectory && new File(dataDir, "tai-utc.dat").isFile) {
      val manager = DataContext.getDefault.getDataProvidersManager;
      manager.addProvider(new DirectoryCrawler(dataDir));
      _hasData = true
      true
    } else false
  }

  def loadArchiveData (f: File): Boolean = synchronized {
    val manager = DataContext.getDefault.getDataProvidersManager
    manager.addProvider(new ZipJarCrawler( f))
    _hasData = true
    true
  }

  //--- conversions between basic Orekit and RACE types

  implicit def oreTleFromTLE (tle: TLE): OreTLE = {
    new OreTLE(tle.line1, tle.line2)
  }

  implicit def absoluteDateFromDateTime (d: DateTime): AbsoluteDate = {
    val (year,month,day,hour,min,sec,millis) = d.getYMDT
    new AbsoluteDate(year,month,day,hour,min, sec.toDouble + millis.toDouble/1000, UTC)
  }

  implicit def dateTimeFromAbsoluteDate (a: AbsoluteDate): DateTime = {
    DateTime.ofEpochMillis( a.toDate(UTC).getTime) // not very efficient
  }

  implicit def geoPosFromGeodeticPoint (gp: GeodeticPoint): GeoPosition = {
    GeoPosition( Radians(gp.getLatitude), Radians(gp.getLongitude), Meters(gp.getAltitude))
  }

  implicit def geodeticPointFromGeoPos (pos: GeoPosition): GeodeticPoint = {
    new GeodeticPoint(pos.lat.toRadians, pos.lon.toRadians, pos.altMeters)
  }

  //--- some useful objects we most likely need - note that accessing these might trigger data load from classpath (if not yet loaded)

  /** geodetic reference frame */
  lazy val ECF: FactoryManagedFrame = {
    ensureData()
    FramesFactory.getITRF( IERSConventions.IERS_2010,true)
  }

  lazy val Earth: OneAxisEllipsoid = {
    ensureData()
    new OneAxisEllipsoid( Constants.WGS84_EARTH_EQUATORIAL_RADIUS, Constants.WGS84_EARTH_FLATTENING, ECF)
  }

  lazy val UTC: UTCScale = {
    ensureData()
    TimeScalesFactory.getUTC
  }

  val MeanEarthRadius: Length = Kilometers(6371)

  //--- utility functions

  @inline def centerDistance(ellipsoid: OneAxisEllipsoid, gp: GeodeticPoint): Double = {
    val p = ellipsoid.transform(gp)
    squareRoot( squared(p.getX) + squared(p.getY) + squared(p.getZ))
  }

  @inline def revPerDay (meanMotionRadSec: Double): Double = meanMotionRadSec * 13750.98708313975701  // 86400.0 / Angle.TwoPi

  @inline def orbitalPeriod (revPerDay: Double): Time = Seconds(86400.0 / revPerDay)
  @inline def orbitalPeriod (tle: TLE): Time = orbitalPeriod(tle.revPerDay)
  @inline def orbitalPeriod (tle: OreTLE): Time = orbitalPeriod(revPerDay(tle.getMeanMotion))

  def orbitalPeriodFromTleLine2 (line2: String): Time = {
    orbitalPeriod( line2.substring(52, 63).toDouble)
  }

  def satIdFromTleLine2(line2: String): Int = {
    line2.substring(2, 7).toInt
  }

  // FIXME - using mean radius is too far off for LEOs with low eccentricity

  def estimatePerigeeHeight (revPerDay: Double, eccentricity: Double): Length = {
    val a = Math.pow( 8681663.653 / revPerDay, 2.0/3) // semi-major axis
    Kilometers(a * (1.0 - eccentricity) - MeanEarthRadius.toKilometers)
  }

  def estimateApogeeHeight (revPerDay: Double, eccentricity: Double): Length = {
    val a = Math.pow( 8681663.653 / revPerDay, 2.0/3) // semi-major axis
    Kilometers(a * (1.0 + eccentricity) - MeanEarthRadius.toKilometers)
  }

  def estimatePerigeeHeight (tle: OreTLE): Length = estimatePerigeeHeight( revPerDay(tle.getMeanMotion), tle.getE)
  def estimatePerigeeHeight (tle: TLE): Length = estimatePerigeeHeight( tle.revPerDay, tle.getE)

  def estimateApogeeHeight (tle: OreTLE): Length = estimateApogeeHeight( revPerDay(tle.getMeanMotion), tle.getE)
  def estimateApogeeHeight (tle: TLE): Length = estimateApogeeHeight( tle.revPerDay, tle.getE)

  def getMinElevationForScanAngle (tle: OreTLE, scanAngle: Angle): Angle = {
    val p = estimatePerigeeHeight(tle) + MeanEarthRadius
    Acos( p/MeanEarthRadius * Sin(scanAngle))
  }

  def getMinElevationForScanAngle (tle: TLE, scanAngle: Angle): Angle = {
    val p = estimatePerigeeHeight(tle) + MeanEarthRadius
    Acos( p/MeanEarthRadius * Sin(scanAngle))
  }

  //--- facade functions

  //--- orbits

  def propagateOrbit (tleLine1: String, tleLine2: String, startDate: DateTime, tInc: Time, endDate: DateTime)
                     (f: (PVCoordinates,Frame,AbsoluteDate)=> Unit): Unit = {
    val oreTLE = new OreTLE(tleLine1,tleLine2)
    val propagator = TLEPropagator.selectExtrapolator(oreTLE)
    val tleFrame: Frame = propagator.getFrame

    val t0: AbsoluteDate = startDate
    val t1: AbsoluteDate = endDate
    val dt: Double = tInc.toSeconds

    var t = t0
    var d = startDate // it's faster and more accurate to increment this ourselves than to go through an AbsoluteDate -> DateTime conversion

    while (t.compareTo(t1) <= 0){
      val state = propagator.propagate(t)
      val pvc = state.getPVCoordinates()

      f( pvc, tleFrame, t)

      t = t.shiftedBy(dt)
      d += tInc
    }
  }

  def getOrbitalTrajectory (tleLine1: String, tleLine2: String, startDate: DateTime, tInc: Time, endDate: DateTime): OrbitalTrajectory = {
    val n = (endDate.timeSince(startDate) / tInc).toInt + 1
    val ot = new OrbitalTrajectory(n, startDate, tInc)
    val ecef = FramesFactory.getITRF(IERSConventions.IERS_2010,true) // getEME2000
    var i = 0

    propagateOrbit( tleLine1, tleLine2, startDate, tInc, endDate) { (pvc, frame, t)=>
      val tf = frame.getTransformTo( ecef, t)
      ot(i) = tf.transformPVCoordinates(pvc).getPosition
      i += 1
    }
    ot
  }
  def getOrbitalTrajectory(tle: TLE, startDate: DateTime, tInc: Time, endDate: DateTime): OrbitalTrajectory = {
    getOrbitalTrajectory(tle.line1, tle.line2, startDate,tInc,endDate)
  }

  def getXyzGroundTrack (tleLine1: String, tleLine2: String, startDate: DateTime, tInc: Time, endDate: DateTime): OrbitalTrajectory = {
    val n = (endDate.timeSince(startDate) / tInc).toInt + 1
    val ot = new OrbitalTrajectory(n, startDate, tInc)
    val ecef = FramesFactory.getITRF(IERSConventions.IERS_2010,true) // getEME2000
    var i = 0
    val p = MutXyz()

    propagateOrbit( tleLine1, tleLine2, startDate, tInc, endDate) { (pvc, frame, t)=>
      val tf = frame.getTransformTo( ecef, t)
      val pos = tf.transformPVCoordinates(pvc).getPosition
      p.setTo( pos.getX, pos.getY, pos.getZ)
      Datum.scaleToEarthRadius(p)
      ot(i) = p
      i += 1
    }
    ot
  }
  def getXyzGroundTrack(tle: TLE, startDate: DateTime, tInc: Time, endDate: DateTime): OrbitalTrajectory = {
    getXyzGroundTrack(tle.line1, tle.line2, startDate,tInc,endDate)
  }

  def getGeoTrajectory(tleLine1: String, tleLine2: String, startDate: DateTime, tInc: Time, endDate: DateTime): Trajectory = {
    val earth = Earth
    val n = (endDate.timeSince(startDate) / tInc).toInt
    val traj = new MutAccurateTrajectory(n)
    var d = startDate

    propagateOrbit( tleLine1, tleLine2, startDate, tInc, endDate) { (pvc, frame, t)=>
      val pos = pvc.getPosition
      val geoPos = earth.transform( pos, frame, t)  // note this is WGS84 latitude
      traj.append( d, Radians(geoPos.getLatitude), Radians(geoPos.getLongitude), Meters(geoPos.getAltitude))
      d += tInc
    }

    traj.snapshot
  }

  def getGeoTrajectory(tle: TLE, startDate: DateTime, tInc: Time, endDate: DateTime): Trajectory = {
    getGeoTrajectory(tle.line1, tle.line2, startDate,tInc,endDate)
  }

  //--- overpasses

  def computeOverpasses (ellipsoid: OneAxisEllipsoid, propagator: AbstractPropagator,
                         startDate: DateTime, duration: Time, groundPoint: GeoPosition,
                         eventHandler: EventHandler[ElevationExtremumDetector],
                         overpasses: ArrayBuffer[Overpass]
                        ): Array[Overpass] = {
    val t0: AbsoluteDate = startDate
    val t1: AbsoluteDate = startDate + duration
    val gp = geodeticPointFromGeoPos(groundPoint)

    val detector = new ElevationExtremumDetector( new TopocentricFrame( ellipsoid, gp, "groundPoint"))
      .withMaxCheck(60)
      .withThreshold(1.0) // seconds
      .withHandler(eventHandler)

    val logger = new EventsLogger
    propagator.addEventDetector(logger.monitorDetector(detector))
    propagator.propagate(t0,t1)

    overpasses.toArray
  }

  //--- filter overpass candidates by elevation

  def computeOverpassesWithElevation ( tleLine1: String, tleLine2: String,
                                       startDate: DateTime, duration: Time, groundPoint: GeoPosition,
                                       minElevation: Angle, maxOverpasses: Int
                                     ): Array[Overpass] = {
    val earth = Earth
    val tle = new OreTLE(tleLine1,tleLine2)
    val propagator = TLEPropagator.selectExtrapolator(tle)
    val overpasses = ArrayBuffer.empty[Overpass]
    val overpassHandler = new OverpassHandler(earth, propagator, groundPoint, maxOverpasses, minElevation.toRadians, overpasses)

    computeOverpasses( earth, propagator, startDate, duration, groundPoint, overpassHandler, overpasses)
  }

  def computeOverpassesWithElevation (tle: TLE, startDate: DateTime, duration: Time, groundPoint: GeoPosition, minElevation: Angle, maxOverpasses: Int): Array[Overpass] = {
    computeOverpassesWithElevation( tle.line1, tle.line2, startDate, duration, groundPoint, minElevation, maxOverpasses)
  }

  /**
   * detector for overpasses of stable LEOs (low excentricity, no change in inclination or mean motion) during given interval
   * we define overpass as the point of maximum elevation of the satellite with respect to the ground pos
   */
  class OverpassHandler ( ellipsoid: OneAxisEllipsoid, propagator: AbstractPropagator,
                          groundPos: GeoPosition, maxOverpasses: Int, minElevation: Double,
                          overpasses: ArrayBuffer[Overpass]
                        ) extends EventHandler[ElevationExtremumDetector] {
    val propFrame = propagator.getFrame
    val gpRadius = {
      val gp = ellipsoid.transform(groundPos)
      squareRoot( squared(gp.getX) + squared(gp.getY) + squared(gp.getZ))
    }

    def computeScanAngle(sp: GeodeticPoint, elev: Double): Double = {
      val spRadius = centerDistance(ellipsoid, sp)
      val beta = elev + π_2
      Math.asin( Math.sin(beta) * gpRadius / spRadius)
    }

    def eventOccurred(state: SpacecraftState, ed: ElevationExtremumDetector, increasing: Boolean): Action = {
      val elev = ed.getElevation(state)
      if (elev >= minElevation) {
        val t = state.getDate
        val sp = ellipsoid.transform( state.getPVCoordinates.getPosition, propFrame, t)
        val scan = computeScanAngle(sp,elev)
        overpasses += Overpass( dateTimeFromAbsoluteDate(t), state, Radians(elev), Radians(scan), groundPos, geoPosFromGeodeticPoint(sp))
      }
      if (overpasses.size > maxOverpasses) Action.STOP else Action.CONTINUE
    }
  }

  //--- filter overpass candidates by max cross-track scan angle of satellite instrument

  // TODO - this should use a FieldOfView detector (e.g. DoubleDihedraFieldOfView) since the scan-angle to elev approximation is inaccurate

  def computeOverpassesWithinScanAngle (tleLine1: String, tleLine2: String,
                                        startDate: DateTime, duration: Time, groundPoint: GeoPosition,
                                        maxScanAngle: Angle, maxOverpasses: Int
                                       ): Array[Overpass] = {
    val earth = Earth
    val tle = new OreTLE(tleLine1,tleLine2)
    val propagator = TLEPropagator.selectExtrapolator(tle)
    val overpasses = ArrayBuffer.empty[Overpass]
    val minElev = getMinElevationForScanAngle( tle, maxScanAngle)
    val overpassHandler = new ScanAngleOverpassHandler(earth, propagator, groundPoint, maxOverpasses, maxScanAngle.toRadians, minElev.toRadians, overpasses)

    computeOverpasses( earth, propagator, startDate, duration, groundPoint, overpassHandler, overpasses)
  }


  class ScanAngleOverpassHandler ( ellipsoid: OneAxisEllipsoid, propagator: AbstractPropagator,
                                   groundPos: GeoPosition, maxOverpasses: Int, maxScanAngle: Double, minElev: Double,
                                   overpasses: ArrayBuffer[Overpass]
                                 ) extends OverpassHandler(ellipsoid,propagator,groundPos,maxOverpasses,minElev,overpasses) {

    override def eventOccurred(state: SpacecraftState, ed: ElevationExtremumDetector, increasing: Boolean): Action = {
      val elev = ed.getElevation(state)
      //if (elev >= minElev) { // might miss points
      if (elev > 0) {
        val t = state.getDate
        val sp = ellipsoid.transform( state.getPVCoordinates.getPosition, propFrame, t)
        val scan = computeScanAngle(sp,elev)
        if (scan < maxScanAngle) {
          overpasses += Overpass(dateTimeFromAbsoluteDate(t), state, Radians(elev), Radians(scan), groundPos, geoPosFromGeodeticPoint(sp))
        }
      }
      if (overpasses.size > maxOverpasses) Action.STOP else Action.CONTINUE
    }
  }

  //--- get first and last overpass times over a set of ground points

  /**
   * TODO - this is not efficient. We should propagate only once and compute elevation angles for each ground-point within
   * that loop
   */
  def computeOverpassPeriods ( tleLine1: String, tleLine2: String,
                               startDate: DateTime, duration: Time, groundPositions: Array[GeoPosition], maxScanAngle: Angle
                             ): Array[OverpassSeq] = {
    val satId = satIdFromTleLine2(tleLine2)
    // we require at least half an orbit between the last and the next first overpass, which means point set cannot span half a meridian
    val maxPeriodDuration = orbitalPeriodFromTleLine2(tleLine2) / 2

    val overpasses: ArrayBuffer[Overpass] = ArrayBuffer.empty
    groundPositions.foreach { gp =>
      val ops = computeOverpassesWithinScanAngle( tleLine1,tleLine2,startDate,duration,gp,maxScanAngle,Int.MaxValue)
      if (ops.nonEmpty) overpasses ++= ops
    }
    overpasses.sortInPlace[Dated]()(DatedOrdering)

    val periods = ArrayBuffer.empty[OverpassSeq]
    var last: Overpass = null
    var first: Overpass = null
    val curSeq = ArrayBuffer.empty[Overpass]

    overpasses.foreach { op =>
      if (last == null) {
        first = op
      } else {
        if (op.date.timeSince(last.date) > maxPeriodDuration) {
          periods += OverpassSeq( satId, curSeq.toArray, groundPositions)
          first = op
          curSeq.clear()
        }
      }

      curSeq += op
      last = op
    }
    if (first != null) periods += OverpassSeq( satId, curSeq.toArray, groundPositions)

    periods.toArray
  }

  def computeOverpassPeriods (tle: TLE, startDate: DateTime, duration: Time, groundPositions: Array[GeoPosition], maxScanAngle: Angle): Array[OverpassSeq] = {
    computeOverpassPeriods(tle.line1, tle.line2, startDate, duration, groundPositions, maxScanAngle)
  }
}

//--- actor fragments

trait OreKitActor extends RaceActor {

  def initOreKit(): Boolean = {
    if (!OreKit.hasData) {
      val src = config.getStringOrElse("orekit-data", "../orekit-data")
      if (!OreKit.loadFSData( src)) {
        error(s"no OreKit data found in $src (check ../orekit-data, OREKIT_DATA or -Dorekit.data=..)")
        false
      } else {
        info(s"OreKit data initialized from $src")
        true
      }
    } else true // already initialized
  }
}



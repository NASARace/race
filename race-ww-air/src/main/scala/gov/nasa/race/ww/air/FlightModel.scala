package gov.nasa.race.ww.air

import gov.nasa.race.air.InFlightAircraft
import gov.nasa.worldwind.WorldWind
import gov.nasa.worldwind.geom.Vec4
import gov.nasa.worldwind.ogc.collada.ColladaRoot
import gov.nasa.worldwind.ogc.collada.impl.ColladaController

/**
  * Renderable representing 3D aircraft symbol
  */
class FlightModel[T <: InFlightAircraft](val flightEntry: FlightEntry[T], val root: ColladaRoot)
                                                                extends ColladaController(root) {
  root.setAltitudeMode(WorldWind.ABSOLUTE)
  root.setModelScale(new Vec4(.5,.5,.5))


  def update (newT: T) = {
    root.setPosition(newT)
  }
}

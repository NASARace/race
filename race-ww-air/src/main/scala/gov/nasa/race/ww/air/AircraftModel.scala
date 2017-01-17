package gov.nasa.race.ww.air

import gov.nasa.race.air.InFlightAircraft
import gov.nasa.worldwind.ogc.collada.ColladaRoot
import gov.nasa.worldwind.ogc.collada.impl.ColladaController

/**
  * Renderable representing 3D aircraft symbol
  */
class AircraftModel[T <: InFlightAircraft] (val flightEntry: FlightEntry[T], root: ColladaRoot)
                                                                extends ColladaController(root) {

  def update (newT: T) = {
    root.setPosition(newT)
  }
}

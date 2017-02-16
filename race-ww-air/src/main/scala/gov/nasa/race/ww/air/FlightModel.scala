package gov.nasa.race.ww.air

import gov.nasa.race.ww._
import gov.nasa.race.air.InFlightAircraft
import gov.nasa.race.util.StringUtils
import gov.nasa.worldwind.render.DrawContext
import osm.map.worldwind.gl.obj.ObjRenderable

import scala.util.matching.Regex

/**
  * Renderable representing 3D aircraft model
  */
class FlightModel[T <: InFlightAircraft](pattern: String, src: String, size: Double) extends ObjRenderable(FarAway,src) {

  val regex = StringUtils.globToRegex(pattern)
  setSize(size)


  def matches (spec: String) = StringUtils.matches(spec,regex)

  def update (newT: T) = {
    setPosition(newT)
  }
}

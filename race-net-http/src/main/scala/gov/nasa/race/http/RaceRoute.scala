package gov.nasa.race.http

import akka.http.scaladsl.server.Route
import com.typesafe.config.Config

/**
  * base aggregate type for route infos, which consist of a akka.http Route and an optional (child) RaceActor
  */
trait RaceRouteInfo {
  val config: Config
  def route: Route
}
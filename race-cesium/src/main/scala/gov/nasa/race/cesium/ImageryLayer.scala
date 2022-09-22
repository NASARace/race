/*
 * Copyright (c) 2021, United States Government, as represented by the
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
package gov.nasa.race.cesium

import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils.ConfigWrapper

/**
  * object that holds params to modify the display of imagery map tiles
  */
case class ImageryParams (alpha: Double,
                          brightness: Double,
                          contrast: Double,
                          hue: Double,
                          saturation: Double,
                          gamma: Double) {

  def toJs: String = s"{alpha:$alpha,brightness:$brightness,contrast:$contrast,hue:$hue,saturation:$saturation,gamma:$gamma}"
}

object ImageryLayer {

  // default display parameters that can be changed interactively
  val defaultImageryParams = ImageryParams( 1.0, 1.0, 1.0, 0.0, 1.0, 1.0) // the Cesium defaults

  def readImageryParams (cfg: Config): ImageryParams = {
    val alpha = cfg.getDoubleOrElse("alpha", defaultImageryParams.alpha)
    val brightness = cfg.getDoubleOrElse("brightness", defaultImageryParams.brightness)
    val contrast = cfg.getDoubleOrElse("contrast", defaultImageryParams.contrast)
    val hue = cfg.getDoubleOrElse("hue", defaultImageryParams.hue)
    val saturation = cfg.getDoubleOrElse("saturation", defaultImageryParams.saturation)
    val gamma = cfg.getDoubleOrElse("gamma", defaultImageryParams.gamma)

    ImageryParams(alpha,brightness,contrast,hue,saturation,gamma)
  }

  val defaultLayers: Seq[ImageryLayer] = Seq(
    ImageryLayer(
      "arcgis",
      "ArcGIS Geographic",
      "https://services.arcgisonline.com/ArcGIS/rest/services/NatGeo_World_Map/MapServer/",
      "new Cesium.ArcGisMapServerImageryProvider({url:'$URL'})",
      isBase = true,
      proxy = true,
      show = true,
      None
    ),

    ImageryLayer(
      "stamen_terrain",
      "Stamen Terrain",
      "http://tile.stamen.com/terrain",
      "new Cesium.OpenStreetMapImageryProvider({url:'$URL'})",
      isBase = true,
      proxy = true,
      show = false,
      None
    ),

    ImageryLayer(
      "<default>",
      "Bing Aerial",
      "",
      "null",
      isBase = true,
      proxy = false,
      show = false,
      None
    )
  )

  def readConfig (config: Config): Seq[ImageryLayer] = {
    val ilConfigs: Seq[Config] = config.getConfigSeq("imagery-layers")
    if (ilConfigs.isEmpty) {
      defaultLayers
    } else {
      ilConfigs.map{ cfg =>
        ImageryLayer(
          cfg.getString("name"),
          cfg.getString("description"),
          cfg.getString("url"),
          cfg.getString("provider"),
          cfg.getBooleanOrElse("base", false),
          cfg.getBooleanOrElse("proxy", false),
          cfg.getBooleanOrElse("show", false),
          cfg.getOptionalConfig("imagery-params").map(readImageryParams)
        )
      }
    }
  }

  def getDefaultImageryParams (conf: Option[Config]): ImageryParams = {
    conf.map(readImageryParams).getOrElse( defaultImageryParams)
  }
}

/**
  * object representing a Cesium ImageryLayer specification
  * this needs to include everything that is required to instantiate the ImageryLayer on the Cesium (client) side
  */
case class ImageryLayer(
                         name: String,
                         description: String,
                         url: String,
                         provider: String,  // Javascript snippet to instantiate Cesium ImageryProvider (can use $URL placeholder)
                         isBase: Boolean,  // is this a base layer (there is only one at a time)
                         proxy: Boolean,   // can we proxy, i.e. store map-tiles on the server
                         show: Boolean,     // do we initially show this (only one base layer can be shown at a time)
                         imageryParams: Option[ImageryParams]
                       )  {

  def toJs: String = {
    val u = if (proxy) s"${CesiumRoute.imageryPrefix}/$name" else url
    val prov = provider.replace("$URL", u)
    val ip = imageryParams.map(p=> s"imageryParams:${p.toJs}").getOrElse("")
    s"""{name:'$name',description:'$description',url:'$u',provider:$prov,isBase:$isBase,proxy:$proxy,show:$show,$ip}"""
  }
}

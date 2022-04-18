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

object ImageryLayer {

  // default display parameters that can be changed interactively
  val defaultDisplayParams = Array(
    1.0,  // alpha
    0.6,  // brightness
    1.6,  // contrast
    0.0,  // hue
    1.0,  // saturation
    1.0   // gamma
  )

  def readDisplayParams (cfg: Config): Array[Double] = {
    val a = new Array[Double](defaultDisplayParams.length)
    a(0) = cfg.getDoubleOrElse("alpha", defaultDisplayParams(0))
    a(1) = cfg.getDoubleOrElse("brightness", defaultDisplayParams(1))
    a(2) = cfg.getDoubleOrElse("contrast", defaultDisplayParams(2))
    a(3) = cfg.getDoubleOrElse("hue", defaultDisplayParams(3))
    a(4) = cfg.getDoubleOrElse("saturation", defaultDisplayParams(4))
    a(5) = cfg.getDoubleOrElse("gamma", defaultDisplayParams(5))
    a
  }

  val defaultLayers: Seq[ImageryLayer] = Seq(
    ImageryLayer(
      "arcgis",
      "https://services.arcgisonline.com/ArcGIS/rest/services/NatGeo_World_Map/MapServer/",
      "new Cesium.ArcGisMapServerImageryProvider({url:'$URL'})",
      "ArcGIS Geographic",
      isBase = true,
      proxy = true,
      show = true,
      defaultDisplayParams
    ),

    ImageryLayer(
      "stamen_terrain",
      "http://tile.stamen.com/terrain",
      "new Cesium.OpenStreetMapImageryProvider({url:'$URL'})",
      "Stamen Terrain",
      isBase = true,
      proxy = true,
      show = false,
      defaultDisplayParams
    ),

    ImageryLayer(
      "<default>",
      "",
      "null",
      "Bing Aerial",
      isBase = true,
      proxy = false,
      show = false,
      defaultDisplayParams
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
          cfg.getString("url"),
          cfg.getString("provider"),
          cfg.getString("descr"),
          cfg.getBooleanOrElse("base", false),
          cfg.getBooleanOrElse("proxy", false),
          cfg.getBooleanOrElse("show", false),
          readDisplayParams(cfg)
        )
      }
    }
  }
}

/**
  * object representing a Cesium ImageryLayer
  * used in document config.js
  */
case class ImageryLayer(
                         name: String,
                         url: String,
                         provider: String,
                         description: String,
                         isBase: Boolean,  // is this a base layer (there is only one at a time)
                         proxy: Boolean,   // can we proxy, i.e. store map-tiles on the server
                         show: Boolean,     // do we initially show this (only one base layer can be shown at a time)
                         displayParam: Array[Double] // alpha, brightness, contrast, hue, saturation, gamma
                       )

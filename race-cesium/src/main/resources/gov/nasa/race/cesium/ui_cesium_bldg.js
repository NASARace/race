/*
 * Copyright (c) 2023, United States Government, as represented by the
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
import * as uiCesium from "./ui_cesium.js";

// TBD - should come from config
let opts = {
    style: new Cesium.Cesium3DTileStyle( {
        color: {
            conditions: [
                ["${feature['building']} === 'hospital'", "color('#ff00000')"],
                [true, "color('#ffffff')"] // default white
            ]
        },
        // for other features see https://github.com/CesiumGS/3d-tiles/tree/main/specification/Styling
    })
}

Cesium.createOsmBuildingsAsync(opts).then( (tileset) => {
    console.log("osmBuildings 3D tileset initialized")

    uiCesium.viewer.scene.primitives.add(tileset);
})

// TBD - add interactive selection
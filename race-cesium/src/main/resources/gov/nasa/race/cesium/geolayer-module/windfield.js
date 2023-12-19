/*
 * Copyright (c) 2016, United States Government, as represented by the
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
import * as util from "../ui_util.js";

console.log("geolayer module loaded: " +  new URL(import.meta.url).pathname.split("/").pop())

const defaultPointSize = 5;
const defaultOutlineColor = Cesium.Color.BLACK;
const defaultBillboardDC = new Cesium.DistanceDisplayCondition(0, 80000);
const defaultGeometryDC = new Cesium.DistanceDisplayCondition(0, 40000);
const defaultPointDC = new Cesium.DistanceDisplayCondition(0, 150000);
const defaultLabelOffset = new Cesium.Cartesian2(5,5);
const defaultColors = [ Cesium.Color.GOLD, Cesium.Color.YELLOW, Cesium.Color.ORANGE, Cesium.Color.RED ];


export function render (entityCollection, opts) {
    for (const e of entityCollection.values) {
        let props = e.properties;
        let spd = getPropValue(props, "spd");
        let clr = getColor(spd);

        if (e.billboard) { // Points are created as billboards 
            e.billboard = null;

            e.point = {
                pixelSize: (opts.pointSize ? opts.pointSize : defaultPointSize),
                color: clr,
                distanceDisplayCondition: (opts.pointDC ? opts.pointDC : defaultPointDC)
            }
        }

        if (e.polyline) {
            e.polyline.distanceDisplayCondition = (opts.geometryDC ? opts.geometryDC : defaultGeometryDC);
            e.polyline.material = clr;
            e.polyline.width = 2;
        }
    }
}

function getColor(spd) {
    if (spd < 5) return defaultColors[0];  // < 5mph
    if (spd < 10) return defaultColors[1];  // < 10mph
    if (spd < 20) return defaultColors[2];     // < 20mph
    return defaultColors[3]; // > 20mph
}

function getPropValue(props,key) {
    let p = props[key];
    return p ? p._value : undefined;
}
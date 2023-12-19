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
console.log("geolayer module loaded: " +  new URL(import.meta.url).pathname.split("/").pop())

const defaultGeometryDC  = new Cesium.DistanceDisplayCondition(0, 350000);
const defaultBillboardDC = new Cesium.DistanceDisplayCondition(0, 200000);


export function render (entityCollection, opts) {
    for (const e of entityCollection.values) {
        let props = e.properties;

        if (e.polygon) {
            e.polygon.distanceDisplayCondition = defaultGeometryDC;

            let name = getPropValue(props,'NAMELSAD');
            let lat = getPropValue(props,'INTPTLAT');
            let lon = getPropValue(props,'INTPTLON');

            if (name && lat && lon) {
                e.position = Cesium.Cartesian3.fromDegrees(lon, lat);

                // TODO outlineWidth does not work for polygons, we might turn this into polyline

                e.label = {
                    text: name,
                    scale: 0.6,
                    fillColor: opts.stroke,
                    distanceDisplayCondition: (opts.billboardDC ? opts.billboardDC : defaultBillboardDC),
                };
            }
        }
    }
}

function getPropValue(props,key) {
    let p = props[key];
    return p ? p._value : undefined;
}
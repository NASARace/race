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
const defaultGeometryDC = new Cesium.DistanceDisplayCondition(0, 150000);
const defaultPointDC = new Cesium.DistanceDisplayCondition(80000, Number.MAX_VALUE);
const defaultLabelOffset = new Cesium.Cartesian2(5,5);


export function render (entityCollection, opts) {
    for (const e of entityCollection.values) {
        let props = e.properties;

        if (e.billboard && !e.point) {
            if (props && props.category && props.category._value === 'mile-marker') { // no billboards
                e.label = {
                    text: props.distance._value.toString(),
                    scale: 0.5,
                    fillColor: opts.stroke,
                    horizontalOrigin: Cesium.HorizontalOrigin.LEFT,
                    verticalOrigin: Cesium.VerticalOrigin.BOTTOM,
                    pixelOffset: defaultLabelOffset,
                    distanceDisplayCondition: (opts.billboardDC ? opts.billboardDC : defaultBillboardDC),
                };
                e.billboard = null;

                e.point = {
                    pixelSize: (opts.pointSize ? opts.pointSize : defaultPointSize),
                    color: opts.stroke,
                    distanceDisplayCondition: (opts.billboardDC ? opts.billboardDC : defaultBillboardDC),
                }

            } else {
                e.billboard.distanceDisplayCondition = (opts.billboardDC ? opts.billboardDC : defaultBillboardDC);

                if (opts.markerSymbol && opts.markerSymbol.endsWith(".png")) {
                    e.billboard.image = opts.markerSymbol;
                    e.billboard.color = opts.markerColor;
                    e.billboard.horizontalOrigin = Cesium.HorizontalOrigin.CENTER;
                    e.billboard.verticalOrigin = Cesium.VerticalOrigin.CENTER;
                }

                e.point = {
                    pixelSize: (opts.pointSize ? opts.pointSize : defaultPointSize),
                    outlineColor: (opts.outlineColor ? opts.outlineColor : defaultOutlineColor),
                    outlineWidth: (opts.outlineWidth ? opts.outlineWidth : 1),
                    color: (opts.markerColor ? opts.markerColor : e.billboard.color), 
                    distanceDisplayCondition: (opts.pointDC ? opts.pointDC : defaultPointDC)
                };
            }
        }

        if (e.polygon) {
            e.polygon.distanceDisplayCondition = (opts.geometryDC ? opts.geometryDC : defaultGeometryDC);
        }

        if (e.polyline) {
            e.polyline.distanceDisplayCondition = (opts.geometryDC ? opts.geometryDC : defaultGeometryDC);
        }
    }
}

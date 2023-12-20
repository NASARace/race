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
import * as config from "../config.js";
import * as util from "../ui_util.js";
import * as uiCesium from "../ui_cesium.js";

console.log("geolayer module loaded: " +  new URL(import.meta.url).pathname.split("/").pop())

const defaultPointSize = 5;
const defaultOutlineColor = Cesium.Color.BLACK;
const defaultBillboardDC = new Cesium.DistanceDisplayCondition(0, 40000);
const defaultGeometryDC = new Cesium.DistanceDisplayCondition(0, 150000);
const defaultPointDC = new Cesium.DistanceDisplayCondition(40000, Number.MAX_VALUE);

// TODO - this currently does not work with macOS WebGL which renders clamp-to-ground polygons incorrectly
// (3D tiles seem to render correctly so we might translate GeoJSON into 3D tiles in the future)

export function render (entityCollection, opts) {
    let entities = entityCollection.values;
    //let positions = new Array(entities.length);
    //let nPos = 0;

    for (let i=0; i<entities.length; i++) {
        let e = entities[i];

        /*
        if (e.position && e.position._value) {
            let gp = Cesium.Cartographic.fromCartesian(e.position._value); // e.position is a PositionProperty
            if (gp && gp.longitude) {
                //positions[i] = gp;
                //nPos++;
                e.position = Cesium.Cartesian3.fromRadians(gp.longitude, gp.latitude, 10); // above ground
            }
        }
        */

        if (e.billboard && !e.point) {
            e.point = {
                pixelSize: (opts.pointSize ? opts.pointSize : defaultPointSize),
                outlineColor: (opts.outlineColor ? opts.outlineColor : defaultOutlineColor),
                outlineWidth: (opts.outlineWidth ? opts.outlineWidth : 1),
                color: (opts.markerColor ? opts.markerColor : e.billboard.color), 
                distanceDisplayCondition: (opts.pointDC ? opts.pointDC : defaultPointDC),
                disableDepthTestDistance: 20000,
                //height: 10,
                //heightReference: Cesium.HeightReference.RELATIVE_TO_GROUND
            };

            //e.billboard = null;
            e.billboard.distanceDisplayCondition = (opts.billboardDC ? opts.billboardDC : defaultBillboardDC);
            e.billboard.disableDepthTestDistance = 20000;
            e.billboard.color = opts.markerColor;
            e.billboard.horizontalOrigin = Cesium.HorizontalOrigin.CENTER;
            e.billboard.verticalOrigin = Cesium.VerticalOrigin.CENTER;
            //e.billboard.heightReference = Cesium.HeightReference.CLAMP_TO_GROUND;

            if (opts.markerSymbol && opts.markerSymbol.endsWith(".png")) {
                e.billboard.image = opts.markerSymbol;
            }
        }

        if (e.polygon) {
            e.polygon.distanceDisplayCondition = (opts.geometryDC ? opts.geometryDC : defaultGeometryDC);
            e.polygon.height = 0;
            e.polygon.heightReference = Cesium.HeightReference.RELATIVE_TO_GROUND;
        }

        if (e.polyline) {
            e.polyline.distanceDisplayCondition = (opts.geometryDC ? opts.geometryDC : defaultGeometryDC);
        }

        cleanupPropertyNames(e);
    }
    
    //if (nPos) make3D(entities, positions);
}

async function make3D (entities, positions) {
    let tp = await uiCesium.terrainProvider;
    
    Cesium.sampleTerrainMostDetailed(tp, positions).then ( (pos3d) => {        
        let len = pos3d.length;
        for (let i = 0; i< len; i++) {
            let gp = pos3d[i];
            if (gp) {
                let cp = Cesium.Cartesian3.fromRadians(gp.longitude, gp.latitude, gp.height + 5);
                entities[i].position = cp;
            }
        }
    });
}

function cleanupPropertyNames (entity) {
    if (entity.properties && entity.properties.propertyNames) {
        let props = entity.properties;
        // note that 'propertyNames' only has a getter so we have to modify in sity
        if (props.propertyNames) {
            let propNames = props.propertyNames;
            for (var i = 0; i<propNames.length;) {
                let key = propNames[i];
                let v = props[key]._value;
                if (!v && v != 0) {
                    propNames.splice(i,1);
                    delete props[key];
                } else {
                    let newKey = key.toLowerCase();
                    if (! Object.is(newKey,key)) {
                        propNames[i] = newKey;
                        props[newKey] = props[key];
                        delete props[key];
                    }
                    i++;
                }
            }
        }
    }
}
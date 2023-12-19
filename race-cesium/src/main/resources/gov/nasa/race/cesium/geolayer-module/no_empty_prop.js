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
// configurable geolayer post processing module
// used to modify the entity collection created by Cesium.GeoJsonDataSource.load(), before the data source is added to the viewer
// has to export a single 'render(Cesium.EntityCollection, renderOpts)' function

// this sample implementation removes all propertyNames with empty values and turns uppercase keys into lowercase

console.log("geolayer module loaded: " +  new URL(import.meta.url).pathname.split("/").pop())

export function render (entityCollection, renderOpts) {
    for (const e of entityCollection.values) {
        cleanupPropertyNames(e);
    }
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
import * as config from "../config.js";
import * as util from "../ui_util.js";

console.log("geolayer module loaded: " +  new URL(import.meta.url).pathname.split("/").pop())

const defaultPointSize = 5;
const defaultOutlineColor = Cesium.Color.BLACK;
const defaultBillboardDC = new Cesium.DistanceDisplayCondition(0, 80000);
const defaultGeometryDC = new Cesium.DistanceDisplayCondition(0, 150000);
const defaultPointDC = new Cesium.DistanceDisplayCondition(80000, Number.MAX_VALUE);


export function render (entityCollection, opts) {
    for (const e of entityCollection.values) {
        if (e.billboard && !e.point) {
            e.point = {
                pixelSize: (opts.pointSize ? opts.pointSize : defaultPointSize),
                outlineColor: (opts.outlineColor ? opts.outlineColor : defaultOutlineColor),
                outlineWidth: (opts.outlineWidth ? opts.outlineWidth : 1),
                color: (opts.markerColor ? opts.markerColor : e.billboard.color), 
                distanceDisplayCondition: (opts.pointDC ? opts.pointDC : defaultPointDC)
            };

            e.billboard.distanceDisplayCondition = (opts.billboardDC ? opts.billboardDC : defaultBillboardDC);
            if (opts.markerSymbol && opts.markerSymbol.endsWith(".png")) {
                e.billboard.image = opts.markerSymbol;
                e.billboard.color = opts.markerColor;
                e.billboard.horizontalOrigin = Cesium.HorizontalOrigin.CENTER;
                e.billboard.verticalOrigin = Cesium.VerticalOrigin.CENTER;
            }
        }

        if (e.polygon) {
            e.polygon.distanceDisplayCondition = (opts.geometryDC ? opts.geometryDC : defaultGeometryDC);
        }

        if (e.polyline) {
            e.polyline.distanceDisplayCondition = (opts.geometryDC ? opts.geometryDC : defaultGeometryDC);
        }

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
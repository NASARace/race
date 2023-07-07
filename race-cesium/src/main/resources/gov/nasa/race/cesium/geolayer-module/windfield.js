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
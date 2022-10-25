
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

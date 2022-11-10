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
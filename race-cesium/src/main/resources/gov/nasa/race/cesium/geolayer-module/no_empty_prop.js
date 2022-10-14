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
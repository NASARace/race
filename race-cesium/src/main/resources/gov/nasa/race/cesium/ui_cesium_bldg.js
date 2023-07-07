import * as uiCesium from "./ui_cesium.js";

// TBD - should come from config
let opts = {
    style: new Cesium.Cesium3DTileStyle( {
        color: {
            conditions: [
                ["${feature['building']} === 'hospital'", "color('#ff00000')"],
                [true, "color('#ffffff')"] // default white
            ]
        },
        // for other features see https://github.com/CesiumGS/3d-tiles/tree/main/specification/Styling
    })
}

Cesium.createOsmBuildingsAsync(opts).then( (tileset) => {
    console.log("osmBuildings 3D tileset initialized")

    uiCesium.viewer.scene.primitives.add(tileset);
})

// TBD - add interactive selection
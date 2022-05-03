import * as ui from "./ui.js";
import * as uiCesium from "./ui_cesium.js";


ui.registerLoadFunction(function initialize() {

    //uiCesium.viewer.scene.primitives.add(Cesium.createOsmBuildings());

    new Promise((resolve, reject) => {
        setTimeout(() => {
            resolve(Cesium.createOsmBuildings());
        }, 5000)
    }).then((osmBldgs) => {
        console.log("osmBuildings 3D tileset initialized")
        uiCesium.viewer.scene.primitives.add(osmBldgs);
    });
});
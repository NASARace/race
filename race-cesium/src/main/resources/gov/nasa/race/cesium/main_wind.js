// main module for cesium wind field client

import * as config from "./config.js";
import * as ws from "./ws.js";
import * as ui from "./ui.js";
import * as uiCesium from "./ui_cesium.js";
import * as uiWind from "./ui_cesium_wind.js";

// the 'onload' of the document
ui.exportToMain(function initialize() {
    ui.initialize();
    ws.initialize(config.wsURL);

    uiCesium.initialize();
    uiWind.initialize();

    console.log("main initialized");
});

// the 'onunload' of the document
ui.exportToMain(function shutdown() {
    ws.shutdown();
});
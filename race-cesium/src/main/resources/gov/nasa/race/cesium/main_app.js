// main module for cesium app client

import * as config from "./config.js";
import * as ws from "./ws.js";
import * as ui from "./ui.js";
import * as uiCesium from "./ui_cesium.js";
import * as uiTracks from "./ui_cesium_tracks.js";
import * as uiLayers from "./ui_cesium_layers.js";
import * as uiWind from "./ui_cesium_wind.js";
import * as uiBldg from "./ui_cesium_bldg.js";
import * as uiSentinel from "./ui_cesium_sentinel.js"

// the 'onload' of the document
ui.exportToMain(function initialize() {
    //try {
    ui.initialize();
    ws.initialize(config.wsURL);

    uiCesium.initialize();
    uiTracks.initialize();
    uiLayers.initialize();
    uiBldg.initialize();
    uiWind.initialize();
    uiSentinel.initialize();

    console.log("main initialized");

    //} catch (e) {
    //    console.log("error initializing main: " + e);
    //}
});

// the 'onunload' of the document
ui.exportToMain(function shutdown() {
    ws.shutdown();
});
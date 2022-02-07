// main module for cesium app client

import * as config from "./config.js";
import * as ws from "./ws.js";
import * as ui from "./ui.js";
import * as uiCesium from "./ui_cesium.js";
import * as uiTracks from "./ui_cesium_tracks.js";
import * as uiLayers from "./ui_cesium_layers.js";

// the 'onload' of the document
ui.exportToMain(function initialize() {
    //try {
    ui.initialize();
    ws.initialize(config.wsURL);

    uiCesium.initialize();
    uiTracks.initialize();
    uiLayers.initialize();

    console.log("main initialized");

    //} catch (e) {
    //    console.log("error initializing main: " + e);
    //}
});

// the 'onunload' of the document
ui.exportToMain(function shutdown() {
    ws.shutdown();
});
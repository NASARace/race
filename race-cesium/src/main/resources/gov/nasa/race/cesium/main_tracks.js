import * as config from "./config.js";
import * as ws from "./ws.js";
import * as ui from "./ui.js";
import * as uiCesium from "./ui_cesium.js";
import * as uiTracks from "./ui_cesium_tracks.js";

// the 'onload' of the document
ui.exportToMain(function initialize() {
    try {
        ui.initialize();
        ws.initialize(config.wsURL);

        uiCesium.initialize();
        uiTracks.initialize();

        console.log("main initialized");

    } catch (e) {
        console.log("error initializing main: " + e);
    }
});

// the 'onunload' of the document
ui.exportToMain(function shutdown() {
    ws.shutdown();
});
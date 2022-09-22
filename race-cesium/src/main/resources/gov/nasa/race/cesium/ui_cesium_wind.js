import * as ws from "./ws.js";
import * as config from "./config.js";
import * as util from "./ui_util.js";
import { SkipList } from "./ui_data.js";
import * as ui from "./ui.js";
import * as uiCesium from "./ui_cesium.js";
import * as windUtils from "./wind-particles/windUtils.js";
import { ParticleSystem } from "./wind-particles/particleSystem.js"

var viewerParameters = {
    lonRange: new Cesium.Cartesian2(),
    latRange: new Cesium.Cartesian2(),
    pixelSize: 0.0
};

var globeBoundingSphere = new Cesium.BoundingSphere(Cesium.Cartesian3.ZERO, 0.99 * 6378137.0);

// those are just initial values (should come from config)
const userInputDefaults = {
    particlesTextureSize: 32,
    maxParticles: 32 * 32,
    particleHeight: 2000,
    fadeOpacity: 0.99,
    dropRate: 0.002,
    dropRateBump: 0.01,
    speedFactor: 0.2,
    lineWidth: 1.5
};

var windView = undefined; // set during init
var windEntries = new SkipList( // id-sorted display list for trackEntryView
    3, // max skip depth
    (a, b) => a.id < b.id, // sort function
    (a, b) => a.id == b.id // identity function
);


const REMOTE = "";
const LOADING = "…";
const LOADED = "○";
const SHOWING = "●";

var restoreRequestRendering = false;

class WindEntry {
    constructor(windField) {
        this.id = windField.name;
        this.show = windField.show; // layer.show is only the initial value

        this.windField = windField; // the server configured spec - what we get through the web socket
        this.data = undefined; // the wind field data - loaded from server
        this.userInput = {...userInputDefaults };
        this.particleSystem = undefined; // not set before we show
        this.primitives = [];

        this.status = REMOTE;
    }

    setVisible(showIt) {
        if (showIt != this.show) {
            this.show = showIt;

            if (showIt) {
                if (this.status == REMOTE) {
                    this.status = LOADING;
                } else if (this.status == LOADED) { // we already have loaded data 
                    this.status = SHOWING;
                }
                this.load();

                if (uiCesium.isRequestRenderMode) {
                    restoreRequestRendering = true;
                    uiCesium.setRequestRenderMode(false);
                }


            } else { // hide
                if (this.status == SHOWING) {
                    this.status = LOADED;
                }
                this.unload();

                if (restoreRequestRendering) {
                    restoreRequestRendering = false;
                    uiCesium.setRequestRenderMode(true);
                    uiCesium.requestRender();
                }
            }

            ui.updateListItem(windView, this);
        }
    }

    load() {
        if (this.data) {
            this.createParticleSystem(this.data);
        } else {
            loadData(this).then(data => {
                this.createParticleSystem(data);
                this.data = data;
            });
        }
    }

    unload() {
        this.removePrimitives();
        this.particleSystem.release();
        this.particlesSystem = undefined;
    }

    createParticleSystem(data) {
        this.particleSystem = new ParticleSystem(uiCesium.viewer.scene.context, data, this.userInput, viewerParameters);
        this.addPrimitives();
        this.status = LOADED;
        ui.updateListItem(windView, this);
    }

    addPrimitives() {
        let particleSystem = this.particleSystem;

        if (particleSystem) {
            this.primitives = [
                // NOTE - order is important, has to reflect dependencies
                particleSystem.particlesComputing.primitives.calculateSpeed,
                particleSystem.particlesComputing.primitives.updatePosition,
                particleSystem.particlesComputing.primitives.postProcessingPosition,
                particleSystem.particlesRendering.primitives.segments,
                particleSystem.particlesRendering.primitives.trails,
                particleSystem.particlesRendering.primitives.screen
            ]

            this.primitives.forEach(p => uiCesium.viewer.scene.primitives.add(p));
            console.log("wind field primitives added: " + this.id);
            //uiCesium.viewer.scene.primitives.show = true;
        }
    }

    removePrimitives() {
        this.primitives.reverse().forEach(p => uiCesium.viewer.scene.primitives.remove(p));
        this.primitives = [];
    }

    applyViewerParameters() {
        if (this.particleSystem) {
            this.particleSystem.applyViewerParameters(viewerParameters);
        }
    }

    updatePrimitives() {
        if (this.particleSystem) {
            this.removePrimitives();
            this.particleSystem.canvasResize(uiCesium.viewer.scene.context);
            this.addPrimitives();
        }
    }

    updateUserInput() {
        if (this.particleSystem) {
            this.particleSystem.applyUserInput(this.userInput);
        }
    }
}

//--- wind field init


ui.registerLoadFunction(function initialize() {
    windView = initWindView();
    ws.addWsHandler(config.wsUrl, handleWsWindMessages);

    initUserInputControls();
    setupEventListeners();

    console.log("ui_cesium_wind initialized");
});

function initWindView() {
    let view = ui.getList("wind.list");
    if (view) {
        ui.setListItemDisplayColumns(view, ["fit"], [
            { name: "show", width: "1rem", attrs: [], map: e => e.status },
            { name: "id", width: "8rem", attrs: ["alignLeft"], map: e => e.id },
            {
                name: "date",
                width: "6rem",
                attrs: ["fixed", "alignRight"],
                map: e => util.toLocalTimeString(e.windField.date)
            }
        ]);
    }
    return view;
}


function initUserInputControls() {
    var e = undefined;
    //e = ui.getChoice("wind.max_particles");
    //ui.setChoiceItems(e, ["0", "16", "32", "64", "128", "256"], 3);

    e = ui.getSlider("wind.max_particles");
    ui.setSliderRange(e, 0, 128, 16);
    ui.setSliderValue(e, userInputDefaults.particlesTextureSize);

    e = ui.getSlider("wind.height");
    ui.setSliderRange(e, 0, 10000, 500);
    ui.setSliderValue(e, userInputDefaults.particleHeight);

    e = ui.getSlider("wind.fade_opacity");
    ui.setSliderRange(e, 0.9, 1.0, 0.01, new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 2 }));
    ui.setSliderValue(e, userInputDefaults.fadeOpacity);

    e = ui.getSlider("wind.drop");
    ui.setSliderRange(e, 0.0, 0.01, 0.001, new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 3 }));
    ui.setSliderValue(e, userInputDefaults.dropRate);

    e = ui.getSlider("wind.drop_bump");
    ui.setSliderRange(e, 0.0, 0.05, 0.005, new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 3 }));
    ui.setSliderValue(e, userInputDefaults.dropRateBump);

    e = ui.getSlider("wind.speed");
    ui.setSliderRange(e, 0.0, 0.3, 0.05, new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 2 }));
    ui.setSliderValue(e, userInputDefaults.speedFactor);

    e = ui.getSlider("wind.width");
    ui.setSliderRange(e, 0.0, 3.0, 0.5, new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 }));
    ui.setSliderValue(e, userInputDefaults.lineWidth);
}

function handleWsWindMessages(msgType, msg) {
    switch (msgType) {
        case "windField":
            handleWindFieldMessage(msg.windField);
            return true;
        default:
            return false;
    }
}

function handleWindFieldMessage(windField) {
    let e = new WindEntry(windField);
    let idx = windEntries.insert(e);
    ui.insertListItem(windView, e, idx);

    if (windField.show) {
        setTimeout(() => setParticleSystem(e), 3000); // FIXME - only defer if page is loading
    }
}

//--- interaction

var userInputChange = false;

function triggerUserInputChange(windEntry, newInput) {
    if (newInput) userInputChange = true;

    setTimeout(() => {
        if (userInputChange) {
            userInputChange = false;
            triggerUserInputChange(windEntry, false);
        } else {
            windEntry.updateUserInput();
        }
    }, 300);
}

ui.exportToMain(function selectWind(event) {
    let e = event.detail.curSelection;
    if (e) {
        ui.setCheckBox("wind.show", e.show);
        // TODO set numeric widgets here
    }
});

ui.exportToMain(function toggleWind(event) {
    let e = ui.getSelectedListItem(windView);
    if (e) {
        e.setVisible(ui.isCheckBoxSelected(event));
        ui.updateListItem(windView, e);
    }
});

ui.exportToMain(function windMaxParticlesChanged(event) {
    //console.log("max particles: " + ui.getSelectedChoiceValue(event.target));
    let e = ui.getSelectedListItem(windView);
    if (e) {
        let n = ui.getSliderValue(event.target);
        e.userInput.particlesTextureSize = n;
        e.userInput.maxParticles = n * n;
        triggerUserInputChange(e, true);
    }
});

ui.exportToMain(function windFadeOpacityChanged(event) {
    //console.log("fade opacity: " + +ui.getSliderValue(event.target));
    let e = ui.getSelectedListItem(windView);
    if (e) {
        e.userInput.fadeOpacity = ui.getSliderValue(event.target);
        triggerUserInputChange(e, true);
    }
});

ui.exportToMain(function windSpeedChanged(event) {
    //console.log("speed: " + +ui.getSliderValue(event.target));
    let e = ui.getSelectedListItem(windView);
    if (e) {
        e.userInput.speedFactor = ui.getSliderValue(event.target);
        triggerUserInputChange(e, true);
    }
});

ui.exportToMain(function windWidthChanged(event) {
    //console.log("line width: " + +ui.getSliderValue(event.target));
    let e = ui.getSelectedListItem(windView);
    if (e) {
        e.userInput.lineWidth = ui.getSliderValue(event.target);
        triggerUserInputChange(e, true);
    }
});

ui.exportToMain(function windHeightChanged(event) {
    //console.log("height: " + +ui.getSliderValue(event.target));
    let e = ui.getSelectedListItem(windView);
    if (e) {
        e.userInput.particleHeight = ui.getSliderValue(event.target);
        triggerUserInputChange(e, true);
    }
});

ui.exportToMain(function windDropRateChanged(event) {
    //console.log("drop rate: " + +ui.getSliderValue(event.target));
    let e = ui.getSelectedListItem(windView);
    if (e) {
        e.userInput.dropRate = ui.getSliderValue(event.target);
        triggerUserInputChange(e, true);
    }
});

ui.exportToMain(function windDropRateBumpChanged(event) {
    //console.log("drop rate bump: " + +ui.getSliderValue(event.target));
    let e = ui.getSelectedListItem(windView);
    if (e) {
        e.userInput.dropRateBump = ui.getSliderValue(event.target);
        triggerUserInputChange(e, true);
    }
});

//--- viewer callbacks

function updateViewerParameters() {
    let viewer = uiCesium.viewer;

    var viewRectangle = viewer.camera.computeViewRectangle(viewer.scene.globe.ellipsoid);
    var lonLatRange = windUtils.viewRectangleToLonLatRange(viewRectangle);

    viewerParameters.lonRange.x = lonLatRange.lon.min;
    viewerParameters.lonRange.y = lonLatRange.lon.max;
    viewerParameters.latRange.x = lonLatRange.lat.min;
    viewerParameters.latRange.y = lonLatRange.lat.max;

    var pixelSize = viewer.camera.getPixelSize(
        globeBoundingSphere,
        viewer.scene.drawingBufferWidth,
        viewer.scene.drawingBufferHeight
    );

    if (pixelSize > 0) {
        viewerParameters.pixelSize = pixelSize;
    }
}

function setupEventListeners() {
    let scene = uiCesium.viewer.scene;

    uiCesium.viewer.camera.moveStart.addEventListener(() => {
        //scene.primitives.show = false;
    });

    uiCesium.viewer.camera.moveEnd.addEventListener(() => {
        updateViewerParameters();
        windEntries.forEach(e => e.applyViewerParameters());
        //scene.primitives.show = true;
    });

    var resized = false;

    window.addEventListener("resize", () => {
        resized = true;
        //scene.primitives.show = false;
        windEntries.forEach(e => e.removePrimitives());
    });

    scene.preRender.addEventListener(() => {
        if (resized) {
            windEntries.forEach(e => e.updatePrimitives());
            resized = false;
            //scene.primitives.show = true;
        }
    });

    window.addEventListener('particleSystemOptionsChanged', () => {
        //particleSystem.applyUserInput(that.panel.getUserInput());
    });

}

//--- data acquisition and translation

async function loadData(windEntry) {
    await loadNetCDF(windEntry);
    return windEntry.data;
}

function loadNetCDF(windEntry) {
    let url = windEntry.windField.url;

    return new Promise(function(resolve) {
        var request = new XMLHttpRequest();
        request.open('GET', url);
        request.responseType = 'arraybuffer';

        request.onload = function() {
            var arrayToMap = function(array) {
                return array.reduce(function(map, object) {
                    map[object.name] = object;
                    return map;
                }, {});
            }

            var NetCDF = new netcdfjs(request.response);
            let data = {};

            var dimensions = arrayToMap(NetCDF.dimensions);
            data.dimensions = {};
            data.dimensions.lon = dimensions['lon'].size;
            data.dimensions.lat = dimensions['lat'].size;
            data.dimensions.lev = dimensions['lev'].size;

            var variables = arrayToMap(NetCDF.variables);
            var uAttributes = arrayToMap(variables['U'].attributes);
            var vAttributes = arrayToMap(variables['V'].attributes);

            data.lon = {};
            data.lon.array = new Float32Array(NetCDF.getDataVariable('lon').flat());
            data.lon.min = Math.min(...data.lon.array);
            data.lon.max = Math.max(...data.lon.array);

            data.lat = {};
            data.lat.array = new Float32Array(NetCDF.getDataVariable('lat').flat());
            data.lat.min = Math.min(...data.lat.array);
            data.lat.max = Math.max(...data.lat.array);

            data.lev = {};
            data.lev.array = new Float32Array(NetCDF.getDataVariable('lev').flat());
            data.lev.min = Math.min(...data.lev.array);
            data.lev.max = Math.max(...data.lev.array);

            data.U = {};
            data.U.array = new Float32Array(NetCDF.getDataVariable('U').flat());
            data.U.min = uAttributes['min'].value;
            data.U.max = uAttributes['max'].value;

            data.V = {};
            data.V.array = new Float32Array(NetCDF.getDataVariable('V').flat());
            data.V.min = vAttributes['min'].value;
            data.V.max = vAttributes['max'].value;

            windEntry.data = data;
            resolve(data);
        };

        request.send();
    });
}
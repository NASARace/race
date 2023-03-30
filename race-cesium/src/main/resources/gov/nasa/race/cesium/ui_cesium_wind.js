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
        if (this.windField.url.endsWith(".nc")) {
            if (this.data) {
                this.createParticleSystem(this.data);
            } else {
                loadData(this).then(data => {
                    this.createParticleSystem(data);
                    this.data = data;
                });
            }
        } else if (this.windField.url.endsWith("_vector.csv.gz")) {
            loadCsvVector(this);

        } else if (this.windField.url.endsWith("_grid.csv.gz")) {
            loadCsvGrid(this);
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
    createIcon();
    createWindow();

    windView = initWindView();
    ws.addWsHandler(config.wsUrl, handleWsWindMessages);

    initUserInputControls();
    setupEventListeners();

    console.log("ui_cesium_wind initialized");
});

function createIcon() {
    return ui.Icon("wind-icon.svg", "main.toggleWindow(event,'wind')", "wind_icon");
}

function createWindow() {
    return ui.Window("Wind", "wind", "wind-icon.svg")(
        ui.List("wind.list", 10, "main.selectWind(event)"),
        ui.RowContainer()(
            ui.CheckBox("show wind", "main.toggleWind(event)", "wind.show")
        ),
        ui.Panel("display")(
            ui.ColumnContainer("align_right")(
                ui.Slider("max particles", "wind.max_particles", "main.windMaxParticlesChanged(event)"),
                ui.Slider("height", "wind.height", "main.windHeightChanged(event)"),
                ui.Slider("fade opacity", "wind.fade_opacity", "main.windFadeOpacityChanged(event)"),
                ui.Slider("drop", "wind.drop", "main.windDropRateChanged(event)"),
                ui.Slider("drop bump", "wind.drop_bump", "main.windDropRateBumpChanged(event)"),
                ui.Slider("speed", "wind.speed", "main.windSpeedChanged(event)"),
                ui.Slider("width", "wind.width", "main.windWidthChanged(event)")
            )
        )
    );
}

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
    ui.setSliderRange(e, 0.8, 1.0, 0.01, new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 2 }));
    ui.setSliderValue(e, userInputDefaults.fadeOpacity);

    e = ui.getSlider("wind.drop");
    ui.setSliderRange(e, 0.0, 0.01, 0.001, new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 3 }));
    ui.setSliderValue(e, userInputDefaults.dropRate);

    e = ui.getSlider("wind.drop_bump");
    ui.setSliderRange(e, 0.0, 0.05, 0.005, new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 3 }));
    ui.setSliderValue(e, userInputDefaults.dropRateBump);

    e = ui.getSlider("wind.speed");
    ui.setSliderRange(e, 0.0, 0.3, 0.02, new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 2 }));
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


//--- CSV (local wind vectors)

const polyLineColorAppearance = new Cesium.PolylineColorAppearance();

const csvVectorPrefixLine = /^# *length: *(\d+)$/;

function getColor(spd) {
    if (spd < 4.6) return Cesium.Color.BLUE;
    if (spd < 9.19) return Cesium.Color.GREEN;
    if (spd < 13.79) return Cesium.Color.YELLOW;
    if (spd < 18.39) return Cesium.Color.ORANGE;
    return Cesium.Color.RED;
}

async function loadCsvVector(windEntry) {
    let points = new Cesium.PointPrimitiveCollection();
    let vectors = [];
    let i = 0;

    const procLine = (line) => {
        if (i > 1) { // vector line
            let values = util.parseCsvValues(line);
            if (values.length == 7) {
                let p0 = new Cesium.Cartesian3(values[0],values[1],values[2]);
                let p1 = new Cesium.Cartesian3(values[3],values[4],values[5]);

                let spd = values[6];
                let clr = getColor(spd);
        
                points.add({
                    position: p0,
                    pixelSize: 3,
                    color: clr
                });
        
                vectors[i-2] = new Cesium.GeometryInstance({
                    geometry: new Cesium.SimplePolylineGeometry({
                        positions: [p0,p1],
                        colors: [clr]
                    })
                });
            }
        } else if (i > 0) { // header line (ignore)
        } else { // prefix comment line "# columns: X, rows: Y"
            let m = line.match(csvVectorPrefixLine);
            if (m) {
                let len = parseInt(m[1]);
                vectors = Array(len);
            }
        }
        i++;
    };

    await util.forEachTextLine(windEntry.windField.url, procLine);
    console.log("loaded ", i-2, " vectors from ", windEntry.windField.url);

    uiCesium.addPrimitive(points);
    uiCesium.addPrimitive( new Cesium.Primitive({
        geometryInstances: vectors,
        appearance: polyLineColorAppearance
    }));

    return windEntry.data;
}

//--- CSV grid

const csvGridPrefixLine = /^# *nx: *(\d+), *x0: *(.+) *, *dx: *(.+) *, *ny: *(\d+), *y0: *(.+) *, *dy: *(.+)$/;

async function loadCsvGrid(windEntry) {
    let nx, x0, dx, ny, y0, dy; // grid bounds and cell size
    let hs, us, vs, ws; // the data arrays
    let hMin = 1e6, uMin = 1e6, vMin = 1e6, wMin = 1e6;
    let hMax = -1e6, uMax = -1e6, vMax = -1e6, wMax = -1e6;

    let i = 0;

    const procLine = (line) => {
        if (i > 1) { // grid data line
            let values = util.parseCsvValues(line);
            if (values.length == 5) {
                const h = values[0];
                const u = values[1];
                const v = values[2];
                const w = values[3];

                if (h < hMin) hMin = h;
                if (h > hMax) hMax = h;
                if (u < uMin) uMin = u;
                if (u > uMax) uMax = u;
                if (v < vMin) vMin = v;
                if (v > vMax) vMax = v;
                if (w < wMin) wMin = w;
                if (w > wMax) wMax = w;

                const j = i-2;
                hs[j] = h;
                us[j] = u;
                vs[j] = v;
                ws[j] = w;
            }
        } else if (i > 0) { // ignore header line
        } else { // prefix comment line with grid bounds
            let m = line.match(csvGridPrefixLine);
            if (m && m.length == 7) {
                nx = parseInt(m[1]);
                x0 = Number(m[2]);
                dx = Number(m[3]);
                ny = parseInt(m[4]);
                y0 = Number(m[5]);
                dy = Number(m[6]);

                let len = (nx * ny);
                hs = new Float32Array(len);
                us = new Float32Array(len);
                vs = new Float32Array(len);
                ws = new Float32Array(len);
            }
        }
        i++;
    };

    await util.forEachTextLine(windEntry.windField.url, procLine);
    console.log("loaded ", i-2, " grid points from ", windEntry.windField.url);

    let data = {
        dimensions: gridDimensions(nx,ny,1),
        lon: axisData(nx, x0 < 0 ? 360 + x0 : x0, dx),  // normalize to 0..360
        lat: axisData(ny, y0, dy),
        lev: levelData(),
        U: cellData(us, uMin, uMax),
        V: cellData(vs, vMin, vMax)
        //W: cellData(ws, wMin, wMax)
    };
    //console.log("@@ data:", data);

    windEntry.data = data;
    windEntry.createParticleSystem(data);
}

function gridDimensions (nx,ny,nz) {
    return {
        lon: nx,
        lat: ny,
        lev: nz
    };
}

function axisData (nv,v0,dv) {
    let a = new Float32Array(nv);
    for (let i=0, v=v0; i<nv; i++, v += dv) { a[i] = v; }
    let vMin, vMax;
    if (dv < 0) {
        vMin = a[nv-1];
        vMax = a[0];
    } else {
        vMin = a[0];
        vMax = a[nv-1];
    }

    return {
        array: a,
        min: vMin,
        max: vMax
    };
}

function levelData () { // this is NOT h but a 3d grid dimension
    return {
        array: new Float32Array([1]),
        min: 1,
        max: 1
    };
}

function cellData (vs, vMin, vMax) {
    return {
        array: vs,
        min: vMin,
        max: vMax
    };
}


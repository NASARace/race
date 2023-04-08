import * as ws from "./ws.js";
import * as config from "./config.js";
import * as util from "./ui_util.js";
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

const WindFieldType = {
    GRID: "grid",
    VECTOR: "vector",
}

const REMOTE = "";
const LOADING = "…";
const LOADED = "○";
const SHOWING = "●";

const csvGridPrefixLine = /^# *nx: *(\d+), *x0: *(.+) *, *dx: *(.+) *, *ny: *(\d+), *y0: *(.+) *, *dy: *(.+)$/;
const csvVectorPrefixLine = /^# *length: *(\d+)$/;

const polyLineColorAppearance = new Cesium.PolylineColorAppearance();

// one per each windField message received from the server
// wraps the server spec plus our internal state for this windField
class WindFieldEntry {

    // factory method
    static create (windField) {
        if (windField.wfType == WindFieldType.GRID) return new GridFieldEntry(windField);
        if (windField.wfType == WindFieldType.VECTOR) return new VectorFieldEntry(windField);
        throw "unknown windField type: " + windField.wfType;
    }

    static compare (a,b) {  // order: date -> wfType -> originDate
        switch (uiDate.dateCompare(a.date, b.date)) {
            case -1: return -1;
            case 0:
                if (a.wfType == b.wfType) {
                    return util.dateCompare(a.originDate, b.originDate);
                } else {
                    return util.compare(a.wfType, b.wfType);
                }
            case 1: return 1;
        }
    }

    constructor(windField) {
        //--- those come from the server message
        this.id = windField.area;
        this.date = windField.forecastDate; // forecast time for this windfield
        this.originDate = windField.baseDate; // when the windfield was created
        this.wfType = windField.wfType;  // grid or vector
        this.wfSrs = windField.srs; // the underlying spatial reference system
        this.url = windField.url; // wherer to get the data
        this.boundaries = windField.boundaries; // rectangle in which windfield was computed

        //--- local data
        this.status = REMOTE;
        this.fHandle = null; // where we store the data locally once it was retrieved from the server

        //--- local display
        this.userInput = {...userInputDefaults };
        this.boundaryEntity = undefined;
    }

    succeeds (other) {
        return (this.date == other.date && this.wfType == other.wfType && this.originDate > other.originDate);
    }

    getFilename() {
        return `${this.id}-${this.wfType}-${this.date}`;
    }

    setStatus (newStatus) {
        this.status = newStatus;
        // update view
    }

    replaceWith (other) {
        if (this.isAreaShowing()){ this.showArea(false); other.showArea(true); }
        if (this.isAnimShowing()){ this.showAnim(false); other.showAnim(true); }
        if (this.isVectorShowing()){ this.showVector(false); other.showVector(true); }
        if (this.isSpeedShowing()){ this.showSpeed(false); other.showSpeed(true); }
    }

    // override respective methods in subclasses

    showAreaBtn() { return ui.createCheckBox( util.isDefined(this.boundaryEntity), toggleShowWindFieldArea) }
    showAnimBtn() { return ""; } // override if we can display wind animation
    showVectorBtn() { return ""; } // override if we can display wind vectors
    showSpeedBtn() { return ""; } // override if we can display wind speed areas

    isAreaShowing() { return util.isDefined(this.boundaryEntity); }
    isAnimShowing() { return false; }
    isVectorShowing() { return false; }
    isSpeedShowing() { return false; }

    showArea (showIt) {}
    showAnim (showIt) {}
    showVector (showIt) {}
    showSpeed (showIt) {}
}

class GridFieldEntry extends WindFieldEntry {
    constructor (windField) {
        super(windField);

        this.particleSystem = undefined; // only lives while we show the grid animation, owns a number of CustomPrimitives
    }

    showAnimBtn() { return ui.createCheckBox(this.isAnimShowing(), toggleShowWindFieldAnim); }

    isAnimShowing() {  return util.isDefined(this.particleSystem); }

    showAnim (showIt) {
        if (showIt) {
            if (!this.particleSystem) {
                if (fHandle) {
                    loadParticleSystemFromFile(); // async
                } else {
                    loadParticleSystemFromUrl(); // async
                }
            } 
        } else {
            if (this.particleSystem) {
                // TODO - do we have to stop rendering first ?
                this.particleSystem.forEachPrimitive( p=> uiCesium.removePrimitive(p));
                this.particleSystem.release();
                this.particleSystem = undefined;
                this.setStatus( LOADED);
            }
        }
    }

    async loadParticleSystemFromUrl() {
        let nx, x0, dx, ny, y0, dy; // grid bounds and cell size
        let hs, us, vs, ws; // the data arrays
        let hMin = 1e6, uMin = 1e6, vMin = 1e6, wMin = 1e6;
        let hMax = -1e6, uMax = -1e6, vMax = -1e6, wMax = -1e6;
    
        let i = 0;
    
        function procLine (line) {
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
        
            return { array: a, min: vMin, max: vMax };
        }
    
        this.setStatus( LOADING);
        await util.forEachTextLine(url, procLine);
        console.log("loaded ", i-2, " grid points from ", windEntry.windField.url);
    
        let data = {
            dimensions: { lon: nx, lat: ny, lev:1 },
            lon: axisData(nx, x0 < 0 ? 360 + x0 : x0, dx),  // normalize to 0..360
            lat: axisData(ny, y0, dy),
            lev: { array: new Float32Array([1]), min: 1, max: 1 },
            U: { array: us, min: uMin, max: uMax },
            V: { array: vs, min: vMin, max: vMax }
            //W: { array: ws, min: wMin, max: wMax }
        };
        //console.log("@@ data:", data);
    
        this.particleSystem = new ParticleSystem(uiCesium.viewer.scene.context, data, this.userInput, viewerParameters);

        this.setStatus( SHOWING);
        this.particleSystem.forEachPrimitive( p=> uiCesium.addPrimitive(p));
    }

    async loadParticleSystemFromFile() {
        console.log("not yet.");
    }

    applyDisplayParameters() {
        if (this.particleSystem) {
            this.particleSystem.applyUserInput(this.userInput);
        }
    }

    // TODO - this needs a general suspend/resume mode, also for pan, zoom etc.
    updatePrimitives() {
        if (this.isAnimShowing()) { 
            this.showAnim(false);
            this.particleSystem.canvasResize(uiCesium.viewer.scene.context);
            this.showAnim(true);
        }
    }
}

class VectorFieldEntry extends WindFieldEntry {
    constructor (windField) {
        super(windField);

        this.vectorPrimitives = undefined; // Cesium.Primitive instantiated when showing the static vector field
    }

    showVectorBtn() { return ui.createCheckBox(this.isVectorShowing(), toggleShowWindFieldVector); }

    isVectorShowing() {  return util.isDefined(this.vectorPrimitives); }

    showVector (showIt) {
        if (showIt) {
            if (!this.vectorPrimitives) {
                if (fHandle) {
                    this.loadVectorsFromFile();
                } else {
                    this.loadVectorsFromUrl();
                }
            }
        } else {
            if (this.vectorPrimitives) {
                this.vectorPrimitives.forEach( p=> uiCesium.removePrimitive(p));
                this.vectorPrimitives = null;
                this.setStatus( LOADED);
            }
        }
    }

    async loadVectorsFromUrl() {
        let points = new Cesium.PointPrimitiveCollection();
        let vectors = [];
        let i = 0;
    
        function procLine (line) {
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
                            // set distanceDisplayCondition here
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
    
        this.setStatus( LOADING);
        await util.forEachTextLine( this.url, procLine);
        console.log("loaded ", i-2, " vectors from ", windEntry.windField.url);
    
        let vectorPrimtive = new Cesium.Primitive({ geometryInstances: vectors, appearance: polyLineColorAppearance});
        this.vectorPrimitives = [ points, vectorPrimitive ];

        uiCesium.addPrimitive(points);
        uiCesium.addPrimitive( vectorPrimitive);
        this.setStatus( SHOWING);
    }

    async loadVectorsFromFile() {
        console.log("not yet.");
    }

    applyDisplayParameters() {
        if (this.vectorPrimtives) {
            console.log("@@ not yet.");
        }
    }
}

//--- module data

const dates = []; // populated from windFieldMessages - this is displayed in timesView
var dateView = undefined; // the list showing available windField times
var selectedDate = undefined;

const entries = new Map();  // populated from windFieldMessages: id->[time+type sorted entries]
var entryView = undefined; // the list showing available wind fields for the selected time
var selectedEntry = undefined;

//--- wind field init

ui.registerLoadFunction(function initialize() {
    createIcon();
    createWindow();

    dateView = initDateView();
    entryView = initEntryView();
    initUserInputControls();

    ws.addWsHandler(config.wsUrl, handleWsWindMessages);
    setupEventListeners();

    console.log("ui_cesium_wind initialized");
});

function createIcon() {
    return ui.Icon("wind-icon.svg", "main.toggleWindow(event,'wind')", "wind_icon");
}

function createWindow() {
    return ui.Window("Wind", "wind", "wind-icon.svg")(
        ui.Panel("wind-fields")(
            ui.RowContainer()(
                ui.ColumnContainer(null,null,"times")(
                    ui.List("wind.dates", 10, "main.selectWindFieldDate(event)"),
                    ui.RowContainer()(
                        ui.Button("next", "main.setNextWindFieldDate()")
                    )
                ),
                ui.HorizontalSpacer(0.5),
                ui.ColumnContainer(null,null,"data sets")(
                    ui.List("wind.entries", 6, "main.selectWindField(event)")
                )
            )
        ),
        ui.Panel("flow display")(
            ui.ColumnContainer("align_right")(
                ui.Slider("max particles", "wind.max_particles", "main.windMaxParticlesChanged(event)"),
                ui.Slider("height", "wind.height", "main.windHeightChanged(event)"),
                ui.Slider("fade opacity", "wind.fade_opacity", "main.windFadeOpacityChanged(event)"),
                ui.Slider("drop", "wind.drop", "main.windDropRateChanged(event)"),
                ui.Slider("drop bump", "wind.drop_bump", "main.windDropRateBumpChanged(event)"),
                ui.Slider("speed", "wind.speed", "main.windSpeedChanged(event)"),
                ui.Slider("width", "wind.width", "main.windWidthChanged(event)")
            )
        ),
        uiPanel("vector display")(
            ui.Slider("point size", "wind.point_size", "main.windPointSizeChanged(event)")
        )
    );
}

function initDateView() {
    let view = ui.getList("wind.dates");
    if (view) {
        ui.setListItemDisplayColumns(view, ["fit"], [
            { name: "forecast", width: "10rem",  attrs: ["fixed", "alignRight"], map: e => util.toLocalDateHMTimeString(e) }
        ]);
    }
    return view;
}

function initEntryView() {
    let view = ui.getList("wind.entries");
    if (view) {
        ui.setListItemDisplayColumns(view, ["header"], [
            { name: "status", width: "1rem", attrs: [], map: e => e.status },
            { name: "id", width: "8rem", attrs: ["alignLeft"], map: e => e.id },
            { name: "age", width: "3rem", attrs: ["fixed", "alignRight"], map: e => util.hoursBetween(e.originDate, e.date) },
            { name: "type", width: "4rem", attrs:[], map: e=> e.wfType },
            ui.listItemSpacerColumn(),
            { name: "area", tip: "toggle field area", width: "2.1rem", attrs: [], map: e => e.showAreaBtn() },
            { name: "spd", tip: "toggle wind speed map", width: "2.1rem", attrs: [], map: e => e.showSpeeddBtn() },
            { name: "vec", tip: "toggle wind vectors", width: "2.1rem", attrs: [], map: e => e.showVectorBtn() },
            { name: "anim", tip: "toggle wind animation", width: "2.1rem", attrs: [], map: e => e.showAnimBtn() }
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

//--- WS messages

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
    let we = WindFieldEntry.create(windField);

    let date = windField.date; // the (forecast) date
    let idx = uiData.sortInUnique( dates, date, uiDate.dateCompare);    
    if (idx >= 0) ui.insertListItem( timesView, date, idx);

    let id = we.id;
    let es = entries.get(id);
    if (es) {
        addEntry(es,we);
    } else { // first entry for this id
        entries.set(id, [we]);
    }
    
    if (date == selectedDate) updateWindFieldView();
}

// we only keep the newest forecast for a given time
function addEntry (windEntries, we) {
    for (let i=0; i<windEntries.length; i++) {
        let oe = windEntries[i];
        if (oe.date > we.date) { // new time entry, nothing to replace
            windEntries.splice(i,0,we);
        } else if (we.succeeds(oe)){
            we.replacePrimitivesOf(oe); // in case oe is showing
            windEntries[i] = we;
        }
    }
}

//--- interaction

function toggleShow (event, showFunc) {
    let cb = ui.getCheckBox(event.target);
    if (cb) {
        let showIt = ui.isCheckBoxSelected(cb);
        let we = ui.getListItemOfElement(cb);
        if (we) showFunc(we,showIt);
    }
}

ui.exportToMain(function toggleShowWindFieldArea(event) {
    toggleShow( event, (we,showIt) => we.showArea(showIt));
});

ui.exportToMain(function toggleShowWindFieldAnim(event) {
    toggleShow( event, (we,showIt) => we.showAnim(showIt));
});

ui.exportToMain(function toggleShowWindFieldVector(event) {
    toggleShow( event, (we,showIt) => we.showVector(showIt));
});

var userInputChange = false;

ui.exportToMain(function selectWindFieldDate(event) {
    let d = event.detail.curSelection;
    if (d) {
        selectedDate = d;
        console.log("@@ selected date: ", d);
    }
});

ui.exportToMain(function selectWindFieldEntry(event) {
    let we = event.detail.curSelection;
    if (we) {
        selectedEntry = we;
        console.log("@@ selected entry: ", we);
    }
});

// grid animation sliders

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

ui.exportToMain(function setLastWindFieldDate(event) {
    console.log("TBD: lastWindFieldDate");
});

ui.exportToMain(function setNextWindFieldDate(event) {
    console.log("TBD: nextWindFieldDate");
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

//--- data acquisition and display

var restoreRequestRenderMode = false;

function suspendRequestRenderMode (){
    if (uiCesium.isRequestRenderMode) {
        restoreRequestRenderMode = true;
        uiCesium.setRequestRenderMode(false);
    }
}

function resumeRequestRenderMode (){
    if (restoreRequestRenderMode) {
        uiCesium.setRequestRenderMode(true);
        restoreRequestRenderMode = false;
    }
}

//--- CSV (local wind vectors)



function getColor(spd) {
    if (spd < 4.6) return Cesium.Color.BLUE;
    if (spd < 9.19) return Cesium.Color.GREEN;
    if (spd < 13.79) return Cesium.Color.YELLOW;
    if (spd < 18.39) return Cesium.Color.ORANGE;
    return Cesium.Color.RED;
}

async function loadCsvVector(windEntry) {
 
    return windEntry.data;
}

//--- CSV grid based particle system data





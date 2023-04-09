import * as ws from "./ws.js";
import * as config from "./config.js";
import * as util from "./ui_util.js";
import * as ui from "./ui.js";
import * as uiCesium from "./ui_cesium.js";
import * as uiData from "./ui_data.js";
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

    static compareFiltered (a,b) {
        switch (util.compare(a.forecastDate,b.forecastDate)) {
            case -1: return -1;
            case 0: return util.compare(a.wfSource, b.wfSource);
            case 1: return 1;
        }
    }

    constructor(windField) {
        //--- those come from the server message
        this.area = windField.area;
        this.forecastDate = windField.forecastDate; // forecast time for this windfield
        this.baseDate = windField.baseDate; // when the windfield was created
        this.wfType = windField.wfType;  // grid or vector
        this.wfSrs = windField.wfSrs; // the underlying spatial reference system
        this.wfSource = windField.wfSource; // HRRR or station
        this.url = windField.url; // wherer to get the data
        this.bounds = windField.bounds; // rectangle in which windfield was computed

        //--- local data
        this.status = REMOTE;
        this.show = false;
        this.userInput = {...userInputDefaults };
        // assets (such as primitives) are subclass specific
    }

    setStatus (newStatus) {
        this.status = newStatus;
        // update view
    }

    replaceWith (other) {
        console.log("not yet.");
    }

    // override respective methods in subclasses
    setVisible (showIt) {}
}

class GridFieldEntry extends WindFieldEntry {
    constructor (windField) {
        super(windField);
        this.particleSystem = undefined; // only lives while we show the grid animation, owns a number of CustomPrimitives
    }

    static animShowing = 0;

    setVisible (showIt) {
        if (showIt != this.show) {
            if (showIt) {
                GridFieldEntry.animShowing++;
                if (!this.particleSystem) {
                    this.loadParticleSystemFromUrl(); // async
                } else {
                    this.particleSystem.forEachPrimitive( p=> p.show = true);
                    uiCesium.setRequestRenderMode(false);

                }
                this.setStatus( SHOWING);

            } else {
                GridFieldEntry.animShowing--;
                if (this.particleSystem) {
                    // TODO - do we have to stop rendering first ?
                    this.particleSystem.forEachPrimitive( p=> p.show = false);
                    if (GridFieldEntry.animShowing == 0) {
                        uiCesium.setRequestRenderMode(true);
                        uiCesium.requestRender();
                    }
                    this.setStatus( LOADED);
                }
            }
            this.show = showIt;
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
        await util.forEachTextLine(this.url, procLine);
        console.log("loaded ", i-2, " grid points from ", this.url);
    
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
        this.particleSystem.forEachPrimitive( p=> uiCesium.addPrimitive(p));
        uiCesium.setRequestRenderMode(false);
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

    setVisible (showIt) {
        if (showIt != this.show) {
            this.show = showIt;
            if (showIt) {
                if (!this.vectorPrimitives) {
                    this.loadVectorsFromUrl(); // this is async, it will set vectorPrimitives when done
                } else {
                    uiCesium.showPrimitives(this.vectorPrimitives, true);
                }
                this.setStatus( SHOWING);

            } else {
                if (this.vectorPrimitives) {
                    uiCesium.showPrimitives(this.vectorPrimitives, false);
                    this.setStatus( LOADED);
                }
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
            
                    let pp = points.add({
                        position: p0,
                        pixelSize: 4,
                        color: clr
                    });
                    pp.distanceDisplayCondition = new Cesium.DistanceDisplayCondition(0,150000);
                    // pp.scaleByDistance = new Cesium.NearFarScalar(1.5e2, 15, 8.0e6, 0.0);
            
                    vectors[i-2] = new Cesium.GeometryInstance({
                        geometry: new Cesium.PolylineGeometry({
                            positions: [p0,p1],
                            colors: [clr],
                            width: 1.5,
                        }),
                        attributes: {  distanceDisplayCondition: new Cesium.DistanceDisplayConditionGeometryInstanceAttribute(0,50000) }
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
        console.log("loaded ", i-2, " vectors from ", this.url);
    
        let lines = new Cesium.Primitive({ 
            geometryInstances: vectors, 
            appearance: polyLineColorAppearance
        });

        this.vectorPrimitives = [points,lines];
        uiCesium.addPrimitives(this.vectorPrimitives);
    }

    applyDisplayParameters() {
        if (this.vectorPrimtives) {
            console.log("@@ not yet.");
        }
    }
}

class WindFieldArea {
    constructor (area, bounds) {
        this.label = area;
        this.bounds = bounds;
        this.show = false;
        this.entity = undefined; // display
    }

    setVisible (showIt) {

    }

    static compare (a,b) { return util.compare(a.label, b.label); }
}

//--- module data

const windFieldEntries = new Map(); // unique-key -> WindFieldEntry

var areas = [] // sorted list of areas
var areaView = undefined;
var selectedArea = undefined;

var selectedType = undefined;

var displayEntries = [];  // time/source sorted entries for selected area and type
var entryView = undefined; // the list showing available wind fields for the selected time
var selectedEntry = undefined;

//--- wind field init

ui.registerLoadFunction(function initialize() {
    createIcon();
    createWindow();

    areaView = ui.getChoice("wind.areas");
    entryView = initEntryView();
    initUserInputControls();
    selectedType = "vector";

    ws.addWsHandler(config.wsUrl, handleWsWindMessages);
    setupEventListeners();

    console.log("ui_cesium_wind initialized");
});

function createIcon() {
    return ui.Icon("wind-icon.svg", (e)=> ui.toggleWindow(e,'wind'), "wind_icon");
}

function createWindow() {
    return ui.Window("Wind", "wind", "wind-icon.svg")(
        ui.Panel("wind-fields", true)(
            ui.RowContainer()(
                ui.Choice("area","wind.areas",selectArea),
                ui.CheckBox("show area", toggleShowArea),
                ui.HorizontalSpacer(6)
            ),
            ui.RowContainer()(
                ui.Radio("vector", selectVectorWindFields,null,true),
                ui.Radio("anim", selectGridWindFields),
                ui.Radio("contour", selectContourWindFields)
            ),
            ui.List("wind.entries", 6, selectWindFieldEntry)
        ),
        ui.Panel("anim display")(
            ui.ColumnContainer("align_right")(
                ui.Slider("max particles", "wind.max_particles", windMaxParticlesChanged),
                ui.Slider("height", "wind.height", windHeightChanged),
                ui.Slider("fade opacity", "wind.fade_opacity", windFadeOpacityChanged),
                ui.Slider("drop", "wind.drop", windDropRateChanged),
                ui.Slider("drop bump", "wind.drop_bump", windDropRateBumpChanged),
                ui.Slider("speed", "wind.speed", windSpeedChanged),
                ui.Slider("width", "wind.width", windWidthChanged)
            )
        ),
        ui.Panel("vector display")(
            ui.Slider("point size", "wind.point_size", gridPointSizeChanged)
        )
    );
}

function initEntryView() {
    let view = ui.getList("wind.entries");
    if (view) {
        ui.setListItemDisplayColumns(view, ["header"], [
            { name: "", width: "2rem", attrs: [], map: e => e.status },
            { name: "forecast", width: "9.5rem",  attrs: ["fixed"], map: e => util.toLocalDateHMTimeString(e.forecastDate) },
            { name: "Δt", tip: "forecast hour", width: "2rem", attrs: ["fixed", "alignRight"], map: e => util.hoursBetween(e.baseDate, e.forecastDate) },
            ui.listItemSpacerColumn(1),
            { name: "src", width: "4rem", attrs:[], map: e=> e.wfSource },
            ui.listItemSpacerColumn(2),
            { name: "show", tip: "toggle wind speed contour", width: "2.1rem", attrs: [], map: e => ui.createCheckBox(e.show, toggleShowWindField) },
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
    windFieldEntries.set(windField.url, we);
    addArea(we);
    if (isSelected(we)) updateEntryView();
}

function addArea (we) {
    if (!areas.find( a=> a.label == we.area)) {
        let wa = new WindFieldArea(we.area, we.bounds);
        uiData.sortInUnique( areas, wa, WindFieldArea.compare);
        ui.setChoiceItems(areaView, areas);
        if (!selectedArea) ui.selectChoiceItem(areaView, wa);
    }
}

function updateEntryView() {
    displayEntries = util.filterMapValues(windFieldEntries, we=> isSelected(we));
    displayEntries.sort(WindFieldEntry.compareFiltered);

    ui.setListItems(entryView, displayEntries);
}

function isSelected (we) {
    return (we.area == selectedArea.label) && (we.wfType == selectedType);
}

//--- interaction

function selectArea(event) {
    selectedArea = ui.getSelectedChoiceValue(areaView);
    updateEntryView();
};

function toggleShowArea(event) {
    toggleShow( event, (ae,showIt) => ae.showArea(showIt));
};

function selectGridWindFields(event) {
    selectedType = "grid";
    updateEntryView();
}

function selectVectorWindFields(event) {
    selectedType = "vector";
    updateEntryView();
}

function selectContourWindFields(event) {
    selectedType = "contour";
    updateEntryView();
}

function toggleShowWindField(event) {
    let we = ui.getListItemOfElement(event.target);
    if (we) {
        we.setVisible(ui.isCheckBoxSelected(event));
        ui.updateListItem(entryView, we);
    }
};

var userInputChange = false;


function selectWindFieldEntry(event) {
    selectedEntry = event.detail.curSelection;
}

//--- grid animation sliders

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

function windMaxParticlesChanged(event) {
    //console.log("max particles: " + ui.getSelectedChoiceValue(event.target));
    let e = ui.getSelectedListItem(entryView);
    if (e) {
        let n = ui.getSliderValue(event.target);
        e.userInput.particlesTextureSize = n;
        e.userInput.maxParticles = n * n;
        triggerUserInputChange(e, true);
    }
}

function windFadeOpacityChanged(event) {
    //console.log("fade opacity: " + +ui.getSliderValue(event.target));
    let e = ui.getSelectedListItem(entryView);
    if (e) {
        e.userInput.fadeOpacity = ui.getSliderValue(event.target);
        triggerUserInputChange(e, true);
    }
}

function windSpeedChanged(event) {
    //console.log("speed: " + +ui.getSliderValue(event.target));
    let e = ui.getSelectedListItem(entryView);
    if (e) {
        e.userInput.speedFactor = ui.getSliderValue(event.target);
        triggerUserInputChange(e, true);
    }
}

function windWidthChanged(event) {
    //console.log("line width: " + +ui.getSliderValue(event.target));
    let e = ui.getSelectedListItem(entryView);
    if (e) {
        e.userInput.lineWidth = ui.getSliderValue(event.target);
        triggerUserInputChange(e, true);
    }
}

function windHeightChanged(event) {
    //console.log("height: " + +ui.getSliderValue(event.target));
    let e = ui.getSelectedListItem(entryView);
    if (e) {
        e.userInput.particleHeight = ui.getSliderValue(event.target);
        triggerUserInputChange(e, true);
    }
}

function windDropRateChanged(event) {
    //console.log("drop rate: " + +ui.getSliderValue(event.target));
    let e = ui.getSelectedListItem(entryView);
    if (e) {
        e.userInput.dropRate = ui.getSliderValue(event.target);
        triggerUserInputChange(e, true);
    }
}

function windDropRateBumpChanged(event) {
    //console.log("drop rate bump: " + +ui.getSliderValue(event.target));
    let e = ui.getSelectedListItem(entryView);
    if (e) {
        e.userInput.dropRateBump = ui.getSliderValue(event.target);
        triggerUserInputChange(e, true);
    }
}

//--- vector parameters

function gridPointSizeChanged(event) {
    console.log("not yet.");
}

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
        //windEntries.forEach(e => e.applyViewerParameters());
        //scene.primitives.show = true;
    });

    var resized = false;

    window.addEventListener("resize", () => {
        resized = true;
        //scene.primitives.show = false;
        //windEntries.forEach(e => e.removePrimitives());
    });

    scene.preRender.addEventListener(() => {
        if (resized) {
            //windEntries.forEach(e => e.updatePrimitives());
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
    if (spd < 4.6) return Cesium.Color.WHITE;
    if (spd < 9.19) return Cesium.Color.LIGHTPINK;
    if (spd < 13.79) return Cesium.Color.HOTPINK;
    if (spd < 18.39) return Cesium.Color.FUCHSIA;
    return Cesium.Color.DEEPPINK;
}

async function loadCsvVector(windEntry) {
 
    return windEntry.data;
}

//--- CSV grid based particle system data





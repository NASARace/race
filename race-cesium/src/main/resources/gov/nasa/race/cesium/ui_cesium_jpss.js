import * as config from "./config.js";
import * as ws from "./ws.js";
import * as util from "./ui_util.js";
import * as ui from "./ui.js";
import * as uiCesium from "./ui_cesium.js";

var satelliteEntries = [];
var satelliteView = undefined;
var selSat = undefined;
var selSatOnly = false;

var upcoming = [];  // upcoming overpasses
var upcomingView = undefined;

var pastEntries = [];  // past overpass data
var displayPastEntries = [];  // the pastEntries shown
var pastView = undefined;
var selPast = undefined;
var showPastHistory = false;

var hotspotView = undefined;

var areaAsset = undefined;
var area = undefined;  // bounds as Rectangle

// those will be initialized by config and can be changed interactively
var history = 7; // in days
var resolution = 0.0002; // lat/lon resultion to match pixel positions (in degrees)
var pixelSize = 3;
var timeSteps = undefined;
var tempThreshold = undefined;
var tempThresholdColor = Cesium.Color.YELLOW;
var frpThreshold = undefined;
var frpThresholdColor = Cesium.Color.BLACK;
var zoomHeight = 20000;

// the Cesium assets to display fire pixels
var pixelPrimitive = undefined;
var tempPrimitive = undefined;

var utcClock = undefined;
var now = 0;

class SatelliteEntry {
    constructor(sat) {
        this.satId = sat.satId;
        this.satName = sat.name;
        this.descr = sat.description;

        this.show = sat.show; // can be changed interactively
        this.region = [];  // set by jpssRegion messages

        this.prev = 0; // overpass times updated by clock
        this.next = 0;
    }

    showRegion (cond) {
        console.log("show region for " + this.satId + " = " + cond);
    }
}

// represents a past overpass for which we have hotspot data
class PastEntry {
    constructor(hs) {
        this.satId = hs.satId;
        this.date = hs.date;
        this.hotspots = hs.hotspots;
   
        this.nGood = util.countMatching(hs.hotspots, h=> h.conf == 2);
        this.nTotal = hs.hotspots.length;
        this.nURT = util.countMatching( hs.hotspots, h=> h.version.endsWith("URT"));
    }

    setPixelGrid (res) {
        this.hotspots.forEach( pix=> {
            if (res) {
                let glat = util.roundToNearest(pix.lat, res);
                let glon = util.roundToNearest(pix.lon, res);
        
                let s = util.fmax_4.format(glat) + ',' + util.fmax_4.format(glon);
                let id = util.intern(s); // we don't want gazillions of duplicate strings
        
                pix.glat = glat;
                pix.glon = glon;
                pix.id = id;
            } else {
                pix.glat = undefined;
                pix.glon = undefined;
                pix.id = undefined;
            }
        });
    }

    setPositions (){
        this.hotspots.forEach( h=> {
            h.pos = Cesium.Cartographic.fromDegrees(h.lon, h.lat, 10);
            h.xyzPos = Cesium.Cartographic.toCartesian(h.pos);

            h.bounds = h.bounds.map( p=> Cesium.Cartographic.fromDegrees(p[1], p[0]));
            h.xyzBounds = h.bounds.map( p=> Cesium.Cartographic.toCartesian(p));
        });
    }
}

ui.registerLoadFunction(function initialize() {
    ui.registerClockMonitor( startTimeUpdate);

    satelliteView = initSatelliteView();
    upcomingView = initUpcomingView();
    pastView = initPastView();
    hotspotView = initHotspotView();

    ws.addWsHandler(config.wsUrl, handleWsJpssMessages);

    history = config.jpss.history;
    timeSteps = config.jpss.timeSteps;
    tempThreshold = config.jpss.temp.value;
    tempThresholdColor = config.jpss.temp.color;
    frpThreshold = config.jpss.frp.value;
    frpThresholdColor = config.jpss.frp.color;
    resolution = config.jpss.resolution;
    pixelSize = config.jpss.pixelSize;
    initSliders();

    ui.setCheckBox("jpss.sel_sat", selSatOnly);
    ui.setCheckBox("jpss.show_history", showPastHistory);

    uiCesium.initLayerPanel("jpss", config.jpss, showJpss);
    console.log("ui_cesium_jpss initialized");
});

function startTimeUpdate(clock) {
    utcClock = clock;
    updateNow();
    setInterval( updateNow, 5000);
}

function initSatelliteView() {
    let view = ui.getList("jpss.satellites");
    if (view) {
        ui.setListItemDisplayColumns(view, ["fit", "header"], [
            { name: "", width: "2rem", attrs: [], map: e => ui.createCheckBox(e.show, toggleShowSatellite) },
            { name: "sat", width: "3rem", attrs: [], map: e => e.satName },
            { name: "next", width: "5rem", attrs: ["fixed", "alignRight"], map: e => util.toLocalHMTimeString(e.next) },
            { name: "last", width: "5rem", attrs: ["fixed", "alignRight"], map: e => util.toLocalHMTimeString(e.prev) }

        ]);
    }
    return view;
}

function initUpcomingView() {
    let view = ui.getList("jpss.upcoming");
    if (view) {
        ui.setListItemDisplayColumns(view, ["fit", "header"], [
            { name: "sat", width: "3rem", attrs: [], map: e => satName(e.satId) },
            { name: "cover", width: "3rem", attrs: ["fixed", "alignRight"], map: e => util.f_1.format(e.coverage) },
            { name: "next date", width: "8rem", attrs: ["fixed", "alignRight"], map: e => util.toLocalMDHMString(e.lastDate) }
        ]);
    }
    return view;
}

function initPastView() {
    let view = ui.getList("jpss.past");
    if (view) {
        ui.setListItemDisplayColumns(view, ["fit", "header"], [
            { name: "sat", width: "3rem", attrs: [], map: e => satName(e.satId) },
            { name: "urt", width: "3rem", attrs: ["fixed", "alignRight"], map: e => e.nURT },
            { name: "all", width: "3rem", attrs: ["fixed", "alignRight"], map: e => e.nTotal },
            { name: "last date", width: "8rem", attrs: ["fixed", "alignRight"], map: e => util.toLocalMDHMString(e.date) }
        ]);
    }
    return view;
}

function initHotspotView() {
    let view = ui.getList("jpss.hotspots");
    if (view) {
        ui.setListItemDisplayColumns(view, ["fit", "header"], [
            { name: "sat", width: "3rem", attrs: [], map: e => satName(e.satId) },
            { name: "conf", width: "2rem", attrs: ["fixed", "alignRight"], map: e => e.conf },
            { name: "temp", width: "4rem", attrs: ["fixed", "alignRight"], map: e => util.f_0.format(e.temp) },
            { name: "frp", width: "4rem", attrs: ["fixed", "alignRight"], map: e => util.f_2.format(e.frp) },
            { name: "pos", width:  "11rem", attrs: ["fixed", "alignRight"], map: e => util.formatLatLon(e.lat,e.lon,3)},
            { name: "date", width: "8rem", attrs: ["fixed", "alignRight"], map: e => util.toLocalMDHMString(e.date) }
        ]);
    }
    return view;
}

function initSliders() {
    let e = ui.getSlider('jpss.history');
    ui.setSliderRange(e, 0, 7, 1, util.f_0);
    ui.setSliderValue(e, history);

    e = ui.getSlider('jpss.resolution');
    ui.setSliderRange(e, 0.0000, 0.003, 0.0001, util.fmax_4);
    ui.setSliderValue(e, resolution);

    e = ui.getSlider('jpss.pixsize');
    ui.setSliderRange(e, 3, 8, 1, util.fmax_0);
    ui.setSliderValue(e, pixelSize);

    e = ui.getSlider('jpss.temp');
    ui.setSliderRange(e, 150, 400, 10, util.fmax_0);
    ui.setSliderValue(e, tempThreshold);

    e = ui.getSlider('jpss.frp');
    ui.setSliderRange(e, 5, 50, 5, util.fmax_0);
    ui.setSliderValue(e, frpThreshold);
}

function toggleShowSatellite(event) {
    let cb = ui.getCheckBox(event.target);
    if (cb) {
        let se = ui.getListItemOfElement(cb);
        if (se) {
            se.show = ui.isCheckBoxSelected(cb);
            updateUpcoming();
            updatePast();
            updateHotspots();
            showPixels();
        }
    }
}

function satEntry(satId) {
    return satelliteEntries.find( e=> e.satId == satId);
}

function isSatShowing(satId) {
    let se = satEntry(satId);
    return se ? se.show : false;
}

function satName(satId) {
    let se = satelliteEntries.find( e=> e.satId == satId);
    return se ? se.satName : undefined;
}

function updateNow() {
    now = ui.getClockEpochMillis(utcClock);

    while (upcoming.length > 0 && upcoming[0].lastDate < now) {
        let past = upcoming.shift();
        let se = satelliteEntries.find( e=> e.satId == past.satId);
        if (se) {
            se.next = upcoming.find( ops=> se.satId == ops.satId);
            ui.updateListItem(satelliteView, se);
        }
    }
}

function pastClassifier (he) {
    if (he.nGood > 0) {
        if (he.date > now - util.MILLIS_IN_DAY) return ui.createImage("jpss-asset/fire"); // good pix within 24h
        else return "";
    } else return "";
}

function hotspotClassifier (he) {
    if (he.conf > 1) return ui.createImage("jpss-asset/fire");
    else if (he.conf > 0) return "";
    else return "";
}

function updateUpcoming() {
    let candidates = upcoming;
    candidates = candidates.filter( op=> isSatShowing(op.satId));

    ui.setListItems(upcomingView, candidates);
}

function updatePast() {
    let candidates = pastEntries;
    candidates = candidates.filter( e=> isSatShowing(e.satId));

    displayPastEntries = candidates;

    let lastSel = selPast;
    if (lastSel && !candidates.includes(lastSel)) lastSel = undefined;
    ui.setListItems(pastView, candidates);
    if (lastSel) ui.setSelectedListItem(pastView,lastSel);
}

function updateHotspots() {
    if (selPast){
        let pastIdx = displayPastEntries.indexOf(selPast);
        let maxPast = showPastHistory ? displayPastEntries.length : pastIdx+1;
        let candidates = [];
    
        for (var i = pastIdx; i < maxPast; i++) {
            let e = displayPastEntries[i];
            let hs = e.hotspots;
            for (var j = 0; j < hs.length; j++) {
                let h = hs[j];
                if (!area || uiCesium.withinRect(h.lat, h.lon, area)){
                    candidates.push(h);
                }
            }
        }
        // sort sum(lat,lon)+time to keep history together ?

        ui.setListItems(hotspotView, candidates);

    } else {
        ui.clearList(hotspotView);
    }
}

function handleWsJpssMessages(msgType, msg) {
    switch (msgType) {
        case "jpssSatellites":
            handleSatelliteMessage(msg.jpssSatellites);
            return true;
        case "jpssRegion":
            handleRegionMessage(msg.jpssRegion);
            return true;
        case "jpssOverpass":
            handleOverpassMessage(msg.jpssOverpass);
            return true;
        case "jpssHotspots":
            handleHotspotMessage(msg.jpssHotspots);
            return true;
        default:
            return false;
    }
}

function handleSatelliteMessage(jpssSatellites) {
    jpssSatellites.forEach( s=> satelliteEntries.push( new SatelliteEntry(s)));
    ui.setListItems( satelliteView, satelliteEntries);
}

function handleRegionMessage(jpssRegion) {
    let se = satEntry(jpssRegion.satId);
    if (se) {
        se.region = jpssRegion.region;
    }
}

function handleOverpassMessage(ops) {
    //console.log("overpass: " + JSON.stringify(ops));
    if (ops.lastDate > now) { // only interested in future overpasses
        let idx = upcoming.findIndex( e=> e.lastDate > ops.lastDate); // earliest first order
        if (idx < 0) {
            upcoming.push(ops);
        } else {
            upcoming.splice(idx,0,ops);
        }
        updateUpcoming();

        let se = satEntry(ops.satId);
        if (se && !se.next || ops.lastDate < se.next) {
            se.next = ops.lastDate;
            ui.updateListItem(satelliteView, se);
        }
    }
}

function handleHotspotMessage(hs) {
    let he = new PastEntry(hs);

    let idx = pastEntries.findIndex( e=> e.date < he.date);  // latest first order
    if (idx < 0) {
        pastEntries.push( he); // append
    } else {
        if ((pastEntries[idx].date == hs.date) && (pastEntries[idx].satId == hs.satId)) pastEntries[idx] = he; // replace, corrected version
        else pastEntries.splice(idx, 0, he); // insert

        let se = satEntry(he.satId);
        if (se && se.prev < he.date) { 
            se.prev = he.date;
            ui.updateListItem(satelliteView, se);
        }
    }
    updatePast();
    updateHotspots();

    he.setPixelGrid(resolution);
    he.setPositions();
    // we can't fill in terrain height yet as that would block initial globe rendering
}


// this works on displayHotspotEntries, which are ordered latest-first
function computeHotspotPixels() {
    let pastIdx = displayPastEntries.indexOf(selPast);
    let pixels = [];
    let seen = new Set();
    let maxPast = showPastHistory ? displayPastEntries.length : pastIdx+1;
    let cutOff = selPast.date - util.days(history);

    for (var i = pastIdx; i < maxPast; i++) {
        let e = displayPastEntries[i];

        if (e.date > cutOff) {
            let hs = e.hotspots;
            for (var j = 0; j < hs.length; j++) {
                let pix = hs[j];
                let k = pix.id; // computed from gridded pixel position
                if (k) {
                    if (!seen.has(k)) {
                        seen.add(k);
                        pixels.push(pix);
                    }
                } else { // no grid, just add pixel
                    pixels.push(pix);
                }
            }
        } else { // pixel too old
            break;
        }
    }

    return pixels; // newer pixels should be on top
}

function createPixelAssets(pixels) {
    clearPrimitives();
    if (pixels.length == 0) return;

    let refDate = selPast.date;
    let lastDate = undefined;
    let clr = undefined;
    let clrAttr = undefined;

    let geoms = [];
    let points = [];

    for (var i = 0; i < pixels.length; i++) {
        let pix = pixels[i];

        if (pix.date != lastDate) { // don't create gazillions of color attrs
            clr = getPixelColor(pix, refDate);
            clrAttr = Cesium.ColorGeometryInstanceAttribute.fromColor(clr);
            lastDate = pix.date;
        }

        if (clr) {
            let geom = new Cesium.GeometryInstance({
                geometry: new Cesium.PolygonGeometry({
                    polygonHierarchy: new Cesium.PolygonHierarchy(pix.xyzBounds),
                    //vertexFormat: Cesium.VertexFormat.POSITION_ONLY,
                    vertexFormat : Cesium.PerInstanceColorAppearance.VERTEX_FORMAT
                    //perPositionHeight: true
                }),
                attributes: {
                    color: clrAttr
                }
            })
            geoms.push(geom);

            if (clr === timeSteps[0].color && pix.temp >= tempThreshold) {
                let point = {
                    position: pix.xyzPos,
                    pixelSize: pixelSize,
                    color: tempThresholdColor
                };
                if (pix.frp >= frpThreshold) {
                    point.outlineWidth = 1.0;
                    point.outlineColor = frpThresholdColor;
                }
                points.push(point);
            }
            // TODO - reorder all frp pixels to the top of their timestep
        }
    }

    if (geoms.length > 0) {
        pixelPrimitive = new Cesium.Primitive({
            geometryInstances: geoms,
            allowPicking: false,
            asynchronous: true,
            releaseGeometryInstances: true,
            
            appearance: new Cesium.PerInstanceColorAppearance({
                faceForward: true,
                flat: true,
                translucent: true,
                //renderState: { depthTest: { enabled: false, } }, // this makes it appear always on top but translucent
            }),
        });        
        uiCesium.addPrimitive(pixelPrimitive);
    }

    if (points.length > 0) {
        tempPrimitive = new Cesium.PointPrimitiveCollection({
            blendOption: Cesium.BlendOption.OPAQUE
        });
        points.forEach( p=> tempPrimitive.add(p));
        uiCesium.addPrimitive(tempPrimitive);
    }

    uiCesium.requestRender();
}

function mapToTerrain(pix) {
    if (!pix.xyzPos) {
        uiCesium.withSampledTerrain([pix.pos], 11, ps=> {
            pix.xyzPos = Cesium.Cartographic.toCartesian(ps[0]);
        });

        let vertices = pix.bounds.map(p => Cesium.Cartographic.fromDegrees(p[1], p[0]));
        uiCesium.withSampledTerrain( vertices, 11, ps=> {
            pix.xyzBounds = ps;
        });
    }
}

function getPixelColor(pixel, refDate) {
    let dt = util.hoursFromMillis(refDate - pixel.date);
    for (var i = 0; i < timeSteps.length; i++) {
        let ts = timeSteps[i];
        if (dt < ts.value) {
            return ts.color;
        }
    }

    return timeSteps[timeSteps.length - 1].color; // TODO - shall we use the last as the catch-all?
}

function clearPrimitives() {
    if (pixelPrimitive) uiCesium.removePrimitive(pixelPrimitive);
    pixelPrimitive = undefined;

    if (tempPrimitive) uiCesium.removePrimitive(tempPrimitive);
    tempPrimitive = undefined;
}

function showPrimitives(isVisible) {
    if (tempPrimitive) tempPrimitive.show = isVisible;
    if (pixelPrimitive) pixelPrimitive.show = isVisible;
    uiCesium.requestRender();
}

function showJpss(cond) {
   if (tempPrimitive) tempPrimitive.show = cond;
   if (pixelPrimitive) pixelPrimitive.show = cond;
   if (areaAsset) areaAsset.show = cond;
   uiCesium.requestRender();
}

function showPixels() {
    if (selPast) {
        let pixels = computeHotspotPixels();
        //if (pixels.length > 100) uiCesium.lowerFrameRateFor(pixels.length * 5, 5);
        createPixelAssets(pixels);
    } else {
        clearPrimitives();
    }
}

//--- interaction

ui.exportToMain(function clearHotspots() {
    ui.clearSelectedListItem(pastView);
    clearPrimitives();
    uiCesium.requestRender();
});

ui.exportToMain(function resetDisplayParams() {
    timeSteps = structuredClone(config.jpss.timeSteps);
    tempThreshold = structuredClone(config.jpss.temp);
    frpThreshold = structuredClone(config.jpss.frp);
});

ui.exportToMain(function selectJpssSatellite(event) {
    selSat = ui.getSelectedListItem(satelliteView);
    if (selSatOnly) {
        updateUpcoming();
        updatePast();
    }
});

ui.exportToMain(function toggleJpssSelSatOnly(event) {
    selSatOnly = ui.isCheckBoxSelected(event);
    updateUpcoming();
    updatePast();
});

ui.exportToMain(function toggleShowJpss(event) {
    showPrimitives(ui.isCheckBoxSelected(event.target));
});

ui.exportToMain(function toggleShowJpssRegion(event) {
    let cb = ui.getCheckBox(event.target);
    if (cb) {
        let se = ui.getListItemOfElement(cb);
        if (se) {
            if (se.region) {
                se.showRegion( ui.isCheckBoxSelected(cb));
            } else console.log("no region for selected satellite");
        }
    }
});

ui.exportToMain(function selectJpssPast(event) {
    selPast = ui.getSelectedListItem(pastView);
    if (selPast) showPixels(); else clearPrimitives();
    updateHotspots();
});

ui.exportToMain(function earliestJpssPastTime(event) {
    ui.selectLastListItem(pastView);
});

ui.exportToMain(function earlierJpssPastTime(event) {
    ui.selectNextListItem(pastView);
});

ui.exportToMain(function laterJpssPastTime(event) {
    ui.selectPrevListItem(pastView);
});

ui.exportToMain(function latestJpssPastTime(event) {
    ui.selectFirstListItem(pastView);
});

ui.exportToMain(function clearJpssHotspots(event) {
    ui.clearSelectedListItem(pastView);
    clearPrimitives();
    uiCesium.requestRender();
});

ui.exportToMain(function zoomToJpssPixel(event) {
    let h = ui.getSelectedListItem(event);
    if (h) {
        uiCesium.zoomTo(Cesium.Cartesian3.fromDegrees(h.lon, h.lat, zoomHeight));
    }
});

ui.exportToMain(function toggleJpssShowPastHistory(event) {
    showPastHistory = ui.isCheckBoxSelected(event.target);
    if (selPast) {
        showPixels();
        updateHotspots();
    }
});

//--- interactive area selection

function clearArea() {
    if (area) {
        ui.setField("jpss.bounds", null);
        area = undefined;
        // TODO - reset overpasses, area hotspots
    }
    if (areaAsset) {
        uiCesium.removeEntity(areaAsset);
        areaAsset = undefined;
    }
    uiCesium.requestRender();
}

ui.exportToMain(function pickJpssArea(event) {
    clearArea();

    uiCesium.pickSurfaceRectangle( rect => {
        area = rect;
        ui.setField("jpss.bounds", util.degreesToString([rect.west, rect.south, rect.east, rect.north], util.fmax_3));
        areaAsset = new Cesium.Entity({
            polyline: {
                positions: uiCesium.cartesian3ArrayFromDegreesRect(rect),
                clampToGround: true,
                width: 1,
                material: Cesium.Color.YELLOW
            },
            selectable: false
        });
        uiCesium.addEntity(areaAsset);
        // TODO - filter overpasses, set area hotspots
    });
});

ui.exportToMain(function clearJpssArea(event) {
    clearArea();
});

//--- layer parameters

ui.exportToMain(function setJpssResolution(event) {
    let v = ui.getSliderValue(event.target);
    resolution = v;
    pastEntries.forEach(e => e.setResulotion(resolution));

    if (pixelPrimitive) {
        showPixels(ui.getSelectedListItemIndex(pastView));
    }
});

ui.exportToMain(function setJpssTempThreshold(event) {
});

ui.exportToMain(function setJpssFrpThreshold(event) {
});

ui.exportToMain(function setJpssPixelSize(event) {
    pixelSize = ui.getSliderValue(event.target);
    if (tempPrimitive) {
        const len = tempPrimitive.length;
        for (let i = 0; i < len; ++i) {
            tempPrimitive.get(i).pixelSize = pixelSize;
        }
        uiCesium.requestRender();
    }
});

ui.exportToMain(function setJpssHistory(event) {
});
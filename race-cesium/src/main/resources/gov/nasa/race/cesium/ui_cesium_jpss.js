import * as config from "./config.js";
import * as ws from "./ws.js";
import * as util from "./ui_util.js";
import * as ui from "./ui.js";
import * as uiCesium from "./ui_cesium.js";

var satelliteEntries = [];
var satelliteView = undefined;
var selSat = undefined;
var selSatOnly = false;

var upcoming = [];  // upcoming overpasses (sorted in ascending time, earliest on top)
var upcomingView = undefined;

var pastEntries = [];  // past overpass data (sorted in descending time, latest on top)
var displayPastEntries = [];  // the pastEntries shown
var pastView = undefined;
var selPast = undefined;
var showPastHistory = false;

var hotspotView = undefined;

var areaAsset = undefined;
var area = undefined;  // bounds as Rectangle
var areaInfoLabel = undefined;

// those will be initialized by config and can be changed interactively
var history = 7; // in days
var resolution = 0.0002; // lat/lon resultion to match pixel positions (in degrees)
var pixelSize = 3;
var outlineWidth = 1;
var timeSteps = undefined;
var brightThreshold = undefined;
var brightThresholdColor = Cesium.Color.YELLOW;
var frpThreshold = undefined;
var frpThresholdColor = Cesium.Color.BLACK;
var zoomHeight = 20000;

// the Cesium assets to display fire pixels
var hsFootprintPrimitive = undefined;  // the surface footprint of fire pixels
var hsPointPrimitive = undefined;   // the brightness/frp points

var dataSource = undefined; // for areas etc

var utcClock = undefined;
var now = 0;

class SatelliteEntry {
    constructor(sat) {
        this.satId = sat.satId;
        this.satName = sat.name;
        this.descr = sat.description;

        this.show = sat.show; // can be changed interactively

        this.prev = 0; // overpass times updated by clock
        this.next = 0;

        this.region = [];  // set by jpssRegion messages
        this.regionAsset = undefined;
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

            if (h.bounds){
                h.bounds = h.bounds.map( p=> Cesium.Cartographic.fromDegrees(p[1], p[0]));
                h.xyzBounds = h.bounds.map( p=> Cesium.Cartographic.toCartesian(p));
            }
        });
    }
}

ui.registerLoadFunction(function initialize() {
    ui.registerClockMonitor( startTimeUpdate);

    satelliteView = initSatelliteView();
    upcomingView = initUpcomingView();
    pastView = initPastView();
    hotspotView = initHotspotView();

    dataSource = new Cesium.CustomDataSource("jpss");
    uiCesium.addDataSource(dataSource);

    areaInfoLabel = ui.getLabel("jpss.bounds-info");
    areaInfoLabel.classList.add( "align_right");

    ws.addWsHandler(config.wsUrl, handleWsJpssMessages);

    history = config.jpss.history;
    timeSteps = config.jpss.timeSteps;
    brightThreshold = config.jpss.bright.value;
    brightThresholdColor = config.jpss.bright.color;
    frpThreshold = config.jpss.frp.value;
    frpThresholdColor = config.jpss.frp.color;
    resolution = config.jpss.resolution;
    pixelSize = config.jpss.pixelSize;
    outlineWidth = config.jpss.outlineWidth;
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
            { name: "show", tip: "show/hide satellite", width: "3rem", attrs: [], map: e => ui.createCheckBox(e.show, toggleShowSatellite) },
            { name: "sat", tip: "satellite name", width: "3rem", attrs: [], map: e => e.satName },
            { name: "rgn", tip: "show/hide region", width: "3rem", attrs: [], map: e => ui.createCheckBox(e.showRegion, toggleShowSatelliteRegion) },
            { name: "next", tip: "next upcoming overpass (local)", width: "8rem", attrs: ["fixed", "alignRight"], map: e => util.toLocalMDHMString(e.next) },
            { name: "last", tip: "most recent overpass (local)", width: "8rem", attrs: ["fixed", "alignRight"], map: e => util.toLocalMDHMString(e.prev) }

        ]);
    }
    return view;
}

function initUpcomingView() {
    let view = ui.getList("jpss.upcoming");
    if (view) {
        ui.setListItemDisplayColumns(view, ["fit", "header"], [
            { name: "swt", tip: "show swath/ground track", width: "2rem", attrs: [], map: e => ui.createCheckBox(e.swathEntity, toggleShowUpcomingSwath) },
            { name: "sat", tip: "satellite name", width: "3rem", attrs: [], map: e => satName(e.satId) },
            { name: "cvr", tip: "coverage of region [0-1]", width: "2rem", attrs: ["fixed", "alignRight"], map: e => util.f_1.format(e.coverage) },
            { name: "next date", width: "8rem", attrs: ["fixed", "alignRight"], map: e => util.toLocalMDHMString(e.lastDate) }
        ]);
    }
    return view;
}

function initPastView() {
    let view = ui.getList("jpss.past");
    if (view) {
        ui.setListItemDisplayColumns(view, ["fit", "header"], [
            { name: "swt", tip: "show swath/ground track", width: "2rem", attrs: [], map: e => ui.createCheckBox(e.ops && e.ops.swathEntity, toggleShowPastSwath) },
            { name: "sat", tip: "satellite name", width: "3rem", attrs: [], map: e => satName(e.satId) },
            { name: "urt", tip: "number of ultra-realtime hotspots", width: "2rem", attrs: ["fixed", "alignRight"], map: e => e.nURT },
            { name: "all", tip: "number of all hotspots", width: "3rem", attrs: ["fixed", "alignRight"], map: e => e.nTotal },
            { name: "last date", width: "8rem", attrs: ["fixed", "alignRight"], map: e => util.toLocalMDHMString(e.date) }
        ]);
    }
    return view;
}

function initHotspotView() {
    let view = ui.getList("jpss.hotspots");
    if (view) {
        ui.setListItemDisplayColumns(view, ["fit", "header"], [
            { name: "sat", tip: "satellite name", width: "3rem", attrs: [], map: e => satName(e.satId) },
            { name: "conf", tip: "hotspot confidence [0:low,1:med,2:high]", width: "2rem", attrs: ["fixed", "alignRight"], map: e => e.conf },
            { name: "bright", tip: "hotspot brightness [K]", width: "4rem", attrs: ["fixed", "alignRight"], map: e => util.f_0.format(e.bright) },
            { name: "frp", tip: "hotspot fire radiative power [MW]", width: "4.5rem", attrs: ["fixed", "alignRight"], map: e => util.f_2.format(e.frp) },
            { name: "pos", tip: "position", width:  "11rem", attrs: ["fixed", "alignRight"], map: e => util.formatLatLon(e.lat,e.lon,3)},
            { name: "date", tip: "date of scan", width: "8rem", attrs: ["fixed", "alignRight"], map: e => util.toLocalMDHMString(e.date) }
        ]);
    }
    return view;
}

function initSliders() {
    let e = ui.getSlider('jpss.history');
    ui.setSliderRange(e, 0, 20, 1, util.f_0);
    ui.setSliderValue(e, history);

    e = ui.getSlider('jpss.resolution');
    ui.setSliderRange(e, 0.0000, 0.01, 0.0008, util.fmax_4);
    ui.setSliderValue(e, resolution);

    e = ui.getSlider('jpss.pixsize');
    ui.setSliderRange(e, 0, 8, 1, util.fmax_0);
    ui.setSliderValue(e, pixelSize);

    e = ui.getSlider('jpss.outline');
    ui.setSliderRange(e, 0, 3, 0.5, util.fmax_1);
    ui.setSliderValue(e, outlineWidth);

    e = ui.getSlider('jpss.bright');
    ui.setSliderRange(e, 0, 400, 25, util.fmax_0);
    ui.setSliderValue(e, brightThreshold);

    e = ui.getSlider('jpss.frp');
    ui.setSliderRange(e, 0, 300, 25, util.fmax_0);
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

function toggleShowSatelliteRegion(event) {
    let cb = ui.getCheckBox(event.target);
    if (cb) {
        let se = ui.getListItemOfElement(cb);
        if (se) {
            if (se.regionAsset) {
                dataSource.entities.remove(se.regionAsset);
                se.regionAsset = undefined;
            } else {
                se.regionAsset = createRegionEntity(se);
                dataSource.entities.add(se.regionAsset);
            }
        }
        uiCesium.requestRender();
    }
}

function toggleShowUpcomingSwath (event) {
    let cb = ui.getCheckBox(event.target);
    let isSelected = ui.isCheckBoxSelected(cb);
    let ops = ui.getListItemOfElement(cb);
    if (ops) toggleShowSwath(isSelected, ops);
}

function toggleShowPastSwath (event) {
    let cb = ui.getCheckBox(event.target);
    let isSelected = ui.isCheckBoxSelected(cb);
    let pe = ui.getListItemOfElement(cb);
    if (pe && pe.ops) toggleShowSwath(isSelected, pe.ops);
}

function toggleShowSwath (showIt, ops) {
    if (showIt && !ops.swathEntity) {
        let e = createSwathEntity(ops)
        if (e) {
            dataSource.entities.add( e);
            ops.swathEntity = e;
        }
    } else {
        dataSource.entities.remove(ops.swathEntity)
        ops.swathEntity = undefined;
    }
    uiCesium.requestRender();
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

function updateNow() { // periodic upcoming cleanup (if we didn't get matching hotspots)
    let timeMargin = 5000; // before we remove orphan upcomings (for which we didn't get hotspots)
    let nShift = 0;
    now = ui.getClockEpochMillis(utcClock);

    while (upcoming.length > 0 && (upcoming[0].lastDate + timeMargin) < now) {
        let head = upcoming.shift();
        updateSatEntryNext(head);
        nShift ++;
    }

    if (nShift > 0) {
        updateUpcoming();
    }
}

function updateSatEntryNext (satId) {
    let se = satEntry(satId);
    if (se) {
        let nextUp = upcoming.find(e => e.satId == satId);
        se.next = nextUp.lastDate;
        ui.updateListItem(satelliteView, se);
    }
}

function updateSatEntryLast (satId) {
    let se = satEntry(satId);
    if (se) {
        let last = pastEntries.find( e=> e.satId == satId);
        se.prev = last.date;
        ui.updateListItem(satelliteView, se);
    }
}

function linkUpcoming (pe) {
    let i = upcoming.findIndex( e=> e.satId == pe.satId && util.isWithin(pe.date, e.firstDate, e.lastDate));
    if (i >= 0){
        let ops = upcoming[i];
        pe.ops = ops;

        upcoming.splice(i,1);
        updateUpcoming();
        updateSatEntryNext(ops.satId);
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

function isInArea (lat,lon) {
    return !area || uiCesium.withinRect(lat, lon, area);
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
                if (isInArea(h.lat, h.lon)){
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
    upcoming.push(ops); // earliest upcoming on top
    upcoming.sort( (a,b) => (a,b) => a.lastDate - b.lastDate);
    updateUpcoming();
    updateSatEntryNext(ops.satId);
}

function handleHotspotMessage(hs) {
    let pe = new PastEntry(hs);

    let i = pastEntries.findIndex( e=> e.date == pe.date);  // replace ? could be any past entry
    if (i >= 0) {
        pastEntries[i] = pe;  // replaced (corrected) version
    } else { // sort in
        i = 0;
        while (i < pastEntries.length && pastEntries[i].date > pe.date) i++;
        pastEntries.splice(i, 0, pe); // insert

        let pFirst = pastEntries.find( e=> e.satId == pe.satId);
        if (pFirst === pe) updateSatEntryLast(pe.satId);
        linkUpcoming(pe);
    }

    updatePast();
    updateHotspots();

    pe.setPixelGrid(resolution);
    pe.setPositions();
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
                if (isInArea(pix.lat,pix.lon)) {
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
            let isLastOverpass = (clr === timeSteps[0].color);

            // only show footprints for last hotspots or selected area
            if (isLastOverpass || isInArea(pix.lat, pix.lon)) {
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
            }

            if (isLastOverpass && pix.bright >= brightThreshold) {
                let point = {
                    position: pix.xyzPos,
                    pixelSize: pixelSize,
                    color: brightThresholdColor
                };
                if (pix.frp >= frpThreshold) {
                    point.outlineWidth = outlineWidth;
                    point.outlineColor = frpThresholdColor;
                    // point.scaleByDistance = config.jpss.frpScale // use Cesium.NearFar(nearCameraDist,nearScale,farCameraDist,farScale)
                }
                points.push(point);
            }
            // TODO - reorder all frp pixels to the top of their timestep
        }
    }

    if (geoms.length > 0) {
        hsFootprintPrimitive = new Cesium.Primitive({
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
        uiCesium.addPrimitive(hsFootprintPrimitive);
    }

    if (points.length > 0) {
        hsPointPrimitive = new Cesium.PointPrimitiveCollection({
            blendOption: Cesium.BlendOption.OPAQUE
        });
        points.forEach( p=> hsPointPrimitive.add(p));
        uiCesium.addPrimitive(hsPointPrimitive);
    }

    uiCesium.requestRender();
}

function createRegionEntity (se) {
    if (se.region.length > 0) {
        let cfg = config.jpss;
        let pts = se.region.map( p=> Cesium.Cartesian3.fromDegrees(p[1], p[0]));
        let br = util.getLatLonArrayBoundingRect(se.region);
        let center = util.getRectCenter(br);
        let pos = Cesium.Cartesian3.fromDegrees(center.lon,center.lat);

        return new Cesium.Entity({
            position: pos,
            polygon: {
                hierarchy: pts,
                fill: false,
                outline: true,
                outlineColor: cfg.regionColor,
                height: 1,
                heightReference: Cesium.HeightReference.CLAMP_TO_GROUND,
            },
            label: {
                text: satName(se.satId),
                font: cfg.font,
                fillColor: cfg.regionColor,
                heightReference: Cesium.HeightReference.CLAMP_TO_GROUND
            }
        });

    } else return undefined;
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

function createSwathEntity (ops) {
    let earth = Cesium.Ellipsoid.WGS84
    let cfg = config.jpss;
    let trj = ops.trajectory;
    
    //let pts = trj.map( tp=> earth.scaleToGeodeticSurface(Cesium.Cartesian3.fromElements(tp.x, tp.y, tp.z)));
    // Cesium has bug when rendering corridors with large number of centerline points
    if (trj.length > 40) trj = util.downSampleWithFirstAndLast(trj,40);
    let pts = trj.map( tp=> Cesium.Cartesian3.fromElements(tp.x, tp.y, tp.z));

    let cp = earth.scaleToGeodeticSurface(pts[Math.round(pts.length/2)]);

    let info = `${satName(ops.satId)}\n${util.toLocalDateString(ops.lastDate)}\n${util.toLocalHMTimeString(ops.firstDate)} - ${util.toLocalHMTimeString(ops.lastDate)}`;

    return new Cesium.Entity( {
        position: cp,
        corridor: {
            positions: pts,
            width: 2*ops.swath,
            cornerType: Cesium.CornerType.MITERED,
            material: cfg.swathColor,
            height: 0,
            heightReference: Cesium.HeightReference.CLAMP_TO_GROUND,
            clampToGround: true,
            //distanceDisplayCondition: cfg.swathDC // Cesium BUG ? does not work correctly
        },
        polyline: {
            positions: pts,
            material: cfg.trackColor,
            height: 0,
            heightReference: Cesium.HeightReference.CLAMP_TO_GROUND,
            clampToGround: true,
            //distanceDisplayCondition: cfg.swathDC
        },
        label: {
            text: info,
            font: cfg.font,
            fillColor: cfg.labelColor,
            //heightReference: Cesium.HeightReference.CLAMP_TO_GROUND,
            //distanceDisplayCondition: cfg.swathDC
        }
    });
}

function clearPrimitives() {
    if  (hsFootprintPrimitive || hsPointPrimitive){
        if (hsFootprintPrimitive) uiCesium.removePrimitive(hsFootprintPrimitive);
        hsFootprintPrimitive = undefined;
    
        if (hsPointPrimitive) uiCesium.removePrimitive(hsPointPrimitive);
        hsPointPrimitive = undefined;

        uiCesium.requestRender();
    }
}

function showPrimitives(isVisible) {
    if (hsPointPrimitive) hsPointPrimitive.show = isVisible;
    if (hsFootprintPrimitive) hsFootprintPrimitive.show = isVisible;
    uiCesium.requestRender();
}

function showJpss(cond) {
   if (hsPointPrimitive) hsPointPrimitive.show = cond;
   if (hsFootprintPrimitive) hsFootprintPrimitive.show = cond;
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
    brightThreshold = structuredClone(config.jpss.bright);
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
    if (selPast) {
        showPixels();
    } else {
        clearPrimitives();
    }
    updateHotspots();
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
    ui.setLabelText(areaInfoLabel, null);
    updateHotspots();
    showPixels();
    uiCesium.requestRender();
}

ui.exportToMain(function pickJpssBounds(event) { // mouse selection
    uiCesium.pickSurfaceRectangle( setJpssArea);
});

ui.exportToMain(function setJpssBounds(event) { // text field input (WSEN)
    // TBD
});

function setJpssArea (rect) {
    clearArea();

    area = rect;
    ui.setField("jpss.bounds", util.degreesToString([rect.west, rect.south, rect.east, rect.north], util.fmax_3));

    let du = util.distanceBetweenGeoPos( rect.north,rect.west, rect.north,rect.east);
    let dv = util.distanceBetweenGeoPos( rect.north,rect.west, rect.south,rect.west);
    let sqAcres = util.fmax_0.format(util.squareMetersToAcres( du * dv));
    let duMi = util.fmax_1.format(util.metersToUsMiles(du));
    let dvMi = util.fmax_1.format(util.metersToUsMiles(dv));
    ui.setLabelText(areaInfoLabel, `${duMi} × ${dvMi} miles, ${sqAcres} acres`);

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

    updatePast();
    updateHotspots();
}

ui.exportToMain(function clearJpssBounds(event) {
    clearArea();
});

ui.exportToMain(function zoomToJpssBounds(event) {
    if (area) {
        //let lon = (area.west + area.east) / 2;
        //let lat = (area.south + area.north) / 2;
        //let cameraPos = Cesium.Cartesian3.fromDegrees(lon, lat, 140000);

        let rect = Cesium.Rectangle.fromDegrees( area.west, area.south, area.east, area.north);
        let cameraPos = uiCesium.viewer.camera.getRectangleCameraCoordinates(rect);
        uiCesium.zoomTo(cameraPos);
    }
});

//--- layer parameters

ui.exportToMain(function setJpssResolution(event) {
    let v = ui.getSliderValue(event.target);
    resolution = v;
    pastEntries.forEach(e => e.setPixelGrid(resolution));

    if (hsFootprintPrimitive) {
        showPixels(ui.getSelectedListItemIndex(pastView));
    }
});

ui.exportToMain(function setJpssBrightThreshold(event) {
    brightThreshold = ui.getSliderValue(event.target);
    if (hsPointPrimitive) {
        showPixels(ui.getSelectedListItemIndex(pastView));
    }
});

ui.exportToMain(function setJpssFrpThreshold(event) {
    frpThreshold = ui.getSliderValue(event.target);
    if (hsPointPrimitive) {
        showPixels(ui.getSelectedListItemIndex(pastView));
    }
});

ui.exportToMain(function setJpssPixelSize(event) {
    pixelSize = ui.getSliderValue(event.target);
    if (hsPointPrimitive) {
        const len = hsPointPrimitive.length;
        for (let i = 0; i < len; ++i) {
            hsPointPrimitive.get(i).pixelSize = pixelSize;
        }
        uiCesium.requestRender();
    }
});

ui.exportToMain(function setJpssOutlineWidth(event) {
    outlineWidth = ui.getSliderValue(event.target);
    if (hsPointPrimitive) {
        const len = hsPointPrimitive.length;
        for (let i = 0; i < len; ++i) {
            hsPointPrimitive.get(i).outlineWidth = outlineWidth;
        }
        uiCesium.requestRender();
    }
});

ui.exportToMain(function setJpssHistory(event) {
    history = ui.getSliderValue(event.target);
    if (hsFootprintPrimitive && showPastHistory) {
        showPixels(ui.getSelectedListItemIndex(pastView));
    }
});
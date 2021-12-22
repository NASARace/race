import { SkipList, CircularBuffer } from "./ui_data.js";
import * as util from "./ui_util.js";
import * as ui from "./ui.js";

const NO_PATH = "";
const LINE_PATH = "~";
const WALL_PATH = "≈";

class TrackAssets {
    constructor(symbol, info = null, path = null) {
        this.symbol = symbol;
        this.info = info;
        this.path = path;
    }
}

// object that wraps server-supplied track info with our locally kept trace and display assets
class TrackEntry {
    constructor(track, assets) {
        this.track = track;
        this.assets = assets;

        this.id = track.label;
        this.trace = new CircularBuffer(maxTraceLength);
    }

    assetDisplay() {
        let s = "";
        if (this.assets) {
            if (this.assets.pathEntity) s += LINE_PATH;
            else if (this.assets.wallEntity) s += WALL_PATH;
        }
        return s;
    }
}

var ws = undefined;

var viewer = undefined;
var camera = undefined;

const minLabelDepth = 300.0;

// we keep those in different data sources so that we can control Z-order and 
// bulk enable/disable display more efficiently
var trackInfoDataSource = undefined;
var trajectoryDataSource = undefined;

const trackEntries = new Map(); // id -> TrackEntry
const trackEntryList = new SkipList( // id-sorted list of track objects
    5, // max depth
    (a, b) => a.id < b.id, // sort function
    (a, b) => a.id == b.id // identity function
);

var trackEntryFilter = noTrackEntryFilter;
var trackEntryView = undefined; // our UI element to display trackEntries

function noTrackEntryFilter(track) { return true; } // all tracks are displayed

// the onload function
app.initialize = function() {
    if (ui.initialize()) {
        trackEntryView = initTrackEntryView();

        initCesium();
        initWS();

        console.log("initialized");
    }
}

function initTrackEntryView() {
    let view = ui.getList("console.tracks.list");

    if (view) {
        ui.setListItemDisplayColumns(view, ["fit"], [
            { name: "assets", width: "2rem", attrs: [], map: te => te.assetDisplay() },
            { name: "id", width: "5rem", attrs: ["alignLeft"], map: te => te.id },
            { name: "date", width: "5rem", attrs: ["fixed"], map: te => util.toLocalTimeString(te.track.date) }
        ]);
    }

    return view;
}

// the onunload function
app.shutdown = function() {
    ws.close();
}

function initCesium() {
    viewer = new Cesium.Viewer('cesiumContainer', {
        imageryProvider: imageryProvider,
        terrainProvider: terrainProvider,
        skyBox: false,
        infoBox: false,
        baseLayerPicker: false,
        sceneModePicker: false,
        navigationHelpButton: false,
        homeButton: false,
        timeline: false,
        animation: false
    });

    setCanvasSize();
    window.addEventListener('resize', setCanvasSize);

    viewer.resolutionScale = window.devicePixelRatio; // 2.0
    viewer.scene.fxaa = true;
    //viewer.scene.fxaaOrderIndependentTranslucency = false;

    trackInfoDataSource = new Cesium.CustomDataSource('trackInfo');
    viewer.dataSources.add(trackInfoDataSource);

    if (storeTrackPaths()) {
        trajectoryDataSource = new Cesium.CustomDataSource('trajectory');
        trajectoryDataSource.show = false;
        viewer.dataSources.add(trajectoryDataSource);
        viewer.dataSources.lowerToBottom(trajectoryDataSource);
    }

    // event listeners
    viewer.camera.moveEnd.addEventListener(updateCamera);
    viewer.scene.canvas.addEventListener('mousemove', updateMouseLocation);

    let selHandler = new Cesium.ScreenSpaceEventHandler(viewer.scene.canvas);
    selHandler.setInputAction(trackSelection, Cesium.ScreenSpaceEventType.LEFT_CLICK);
}

// encapsulate in which data source we keep Cesium entities
function addTrackSymbolEntity(e) {
    viewer.entities.add(e);
}

function removeTrackSymbolEntity(e) {
    viewer.entities.remove(e);
}

function addTrackInfoEntity(e) {
    trackInfoDataSource.entities.add(e);
}

function removeTrackInfoEntity(e) {
    trackInfoDataSource.entities.remove(e);
}

function trackSelection() {
    let sel = viewer.selectedEntity;
    if (sel && sel.id) {
        let te = trackEntries.get(sel.id);
        if (te) ui.setSelectedListItem(trackEntryView, te);
    } else {
        ui.clearSelectedListItem(trackEntryView);
    }
}

function storeTrackPaths() {
    return trackPathLength > 0;
}

function updateCamera() {
    let pos = viewer.camera.positionCartographic;
    ui.setField("console.view.altitude", Math.round(pos.height).toString());
}

function updateMouseLocation(e) {
    var ellipsoid = viewer.scene.globe.ellipsoid;
    var cartesian = viewer.camera.pickEllipsoid(new Cesium.Cartesian3(e.clientX, e.clientY), ellipsoid);
    if (cartesian) {
        let cartographic = ellipsoid.cartesianToCartographic(cartesian);
        let longitudeString = Cesium.Math.toDegrees(cartographic.longitude).toFixed(5);
        let latitudeString = Cesium.Math.toDegrees(cartographic.latitude).toFixed(5);

        ui.setField("console.view.latitude", latitudeString);
        ui.setField("console.view.longitude", longitudeString);
    }
}

function setCanvasSize() {
    viewer.canvas.width = window.innerWidth;
    viewer.canvas.height = window.innerHeight;
}

function initWS() {
    if ("WebSocket" in window) {
        console.log("initializing websocket " + wsURL);
        ws = new WebSocket(wsURL);

        ws.onopen = function() {
            // nothing yet
        };

        ws.onmessage = function(evt) {
            try {
                let msg = JSON.parse(evt.data.toString());
                handleServerMessage(msg);
            } catch (error) {
                console.log(error);
                console.log(evt.data.toString());
            }
        }

        ws.onclose = function() {
            console.log("connection is closed...");
        };
    } else {
        console.log("WebSocket NOT supported by your Browser!");
    }
}

function handleServerMessage(msg) {
    //console.log(JSON.stringify(msg));

    var msgType = Object.keys(msg)[0]; // first member name

    switch (msgType) {
        case "track":
            handleTrackMessage(msg.track);
            break;
        case "trackList":
            handleTrackListMessage(msg.trackList);
            break;
        case "camera":
            handleCameraMessage(msg.camera);
            break;
        case "setClock":
            handleSetClock(msg.setClock);
            break;
        default:
            console.log("ignoring unknown server message: " + msgType);
    }
}

function handleSetClock(setClock) {
    ui.setClock("console.clock.time", setClock.time, setClock.timeScale);
    ui.resetTimer("console.clock.elapsed", setClock.timeScale);
    ui.startTime();
}

function handleCameraMessage(newCamera) {
    camera = newCamera;
    setHomeView();
}

//--- track messages

function handleTrackMessage(track) {
    //console.log(JSON.stringify(track));
    updateTrackEntries(track);
}

function handleTrackListMessage(tracks) { // bulk update
    for (track of tracks) updateTrackEntries(track);
}

function updateTrackEntries(track) {
    let pitch = track.pitch ? track.pitch : 0.0;
    let roll = track.roll ? track.roll : 0.0;
    let hpr = Cesium.HeadingPitchRoll.fromDegrees(track.hdg, pitch, roll);

    let pos = Cesium.Cartesian3.fromDegrees(track.lon, track.lat, track.alt);
    let attitude = Cesium.Transforms.headingPitchRollQuaternion(pos, hpr);

    let te = trackEntries.get(track.label);

    if (te) { // update
        if (isTrackTerminated(track)) { // remove
            if (trackEntryFilter(te)) {
                trackEntryList.remove(te)
                ui.removeListItem(trackEntryView, te);
                trackEntries.delete(te.id);
            }
            removeAssets(te);

        } else { // update
            te.trace.push(te.track); // add previous track to trace
            te.track = track;

            if (trackEntryFilter(te)) {
                if (hasTrackIdChanged(track)) {
                    trackEntryList.remove(te);
                    ui.removeListItem(trackEntryView, te);
                    te.id = track.label;
                    let idx = trackEntryList.insert(te);
                    ui.insertListItem(trackEntryView, te, idx);

                } else {
                    ui.updateListItem(trackEntryView, te);
                }

                if (te.assets.symbol) updateTrackSymbol(track, te.assets.symbol, pos, attitude);
                if (te.assets.info) updateTrackInfo(te, pos);
            }
        }

    } else { // new one
        //console.log("add track: " + JSON.stringify(track));
        let assets = new TrackAssets(null, null, null);
        te = new TrackEntry(track, assets);
        trackEntries.set(te.id, te);

        if (trackEntryFilter(te)) {
            assets.symbol = createTrackSymbol(te, pos, attitude);
            assets.info = createTrackInfo(te, pos);

            let idx = trackEntryList.insert(te);
            ui.insertListItem(trackEntryView, te, idx);

            addTrackSymbolEntity(assets.symbol);
            addTrackInfoEntity(assets.info);
        }
    }

    if (te.trace) te.trace.push(track);
}

function isTrackTerminated(track) {
    return (track.status & 0x0c); // 4: dropped, 8: completed
}

function hasTrackIdChanged(track) {
    return (track.status & 0x20);
}

var entityPrototype = undefined;

// TODO - support categories (colors etc)
function createTrackSymbol(trackEntry, pos, attitude) {
    let track = trackEntry.track;

    let sym = new Cesium.Entity({
        id: track.label,
        position: pos,
        orientation: attitude,

        point: entityPrototype ? entityPrototype.point : {
            pixelSize: trackPointSize,
            color: trackColor,
            outlineColor: trackPointOutlineColor,
            outlineWidth: trackPointOutlineWidth,
            distanceDisplayCondition: trackPointDC
        },
        model: entityPrototype ? entityPrototype.model : {
            uri: trackModel,
            color: trackColor,
            //colorBlendMode: Cesium.ColorBlendMode.HIGHLIGHT,
            colorBlendMode: Cesium.ColorBlendMode.MIX,
            colorBlendAmount: 0.5,
            silhouetteColor: trackModelOutlineColor,
            silhouetteAlpha: trackModelOutlineAlpha,
            silhouetteSize: trackModelOutlineWidth,
            minimumPixelSize: trackModelSize,
            distanceDisplayCondition: trackModelDC
        },
        label: {
            text: track.label,
            scale: 0.8,
            horizontalOrigin: Cesium.HorizontalOrigin.LEFT,
            font: trackLabelFont,
            fillColor: trackColor,
            showBackground: true,
            backgroundColor: trackLabelBackground, // alpha does not work against model
            outlineColor: trackColor,
            outlineWidth: 1,
            pixelOffset: trackLabelOffset,
            disableDepthTestDistance: minLabelDepth,
            distanceDisplayCondition: trackLabelDC
        }
        // track paths are separate entities
    });

    if (!entityPrototype) entityPrototype = sym; // first one

    return sym;
}

function updateTrackSymbol(track, sym, pos, attitude) {
    sym.position = pos;
    sym.orientation = attitude;
}

function createTrackInfo(trackEntry, pos) {
    return new Cesium.Entity({
        id: trackInfoLabel(trackEntry),
        position: pos,

        label: {
            font: trackInfoFont,
            scale: 0.8,
            horizontalOrigin: Cesium.HorizontalOrigin.LEFT,
            fillColor: trackColor,
            showBackground: true,
            backgroundColor: trackLabelBackground, // alpha does not work against model
            outlineColor: trackColor,
            outlineWidth: 1,
            pixelOffset: trackInfoOffset,
            disableDepthTestDistance: minLabelDepth,
            distanceDisplayCondition: trackInfoDC
        }
    });
}

function updateTrackInfo(trackEntry, pos) {
    let info = trackEntry.assets.info;
    info.label.text = trackInfoLabel(trackEntry);
    info.position = pos;
}

function trackInfoLabel(trackEntry) {
    let track = trackEntry.track;
    let fl = Math.round(track.alt * 0.00656167979) * 5; // m to ft/100 in 5 increment
    let hdg = Math.round(track.hdg);
    let spd = Math.round(track.spd * 1.94384449); // m/s to knots

    let trace = trackEntry.trace;
    let vr = " ";
    if (trace.size > 0) {
        let lastAlt = trace.last().alt
        if (track.alt > lastAlt) vr = "+";
        else if (track.alt < lastAlt) vr = "-";
    }

    //return `FL${fl} ${hdg}° ${spd}kn`;
    return `${fl}${vr}│${hdg}°│${spd}`;
}

function removeAssets(te) {
    let assets = te.assets;
    if (assets.symbol) removeTrackSymbolEntity(assets.symbol);
    if (assets.info) removeTrackInfoEntity(assets.info);
    if (assets.path); // TODO
}


function updateTrajectory(track) {
    var trajectoryEntities = trajectoryDataSource.entities;
    var e = trajectoryEntities.getById(track.label);

    if (e) {
        /*
        let positions = e.wall.positions.getValue();
        positions.push( Cesium.Cartesian3.fromDegrees( track.lon, track.lat, track.alt));
        e.wall.positions.setValue(positions);
        */

        let positions = e.wall.positions.valueOf();
        positions.push(Cesium.Cartesian3.fromDegrees(track.lon, track.lat, track.alt));
        e.wall.positions = positions;

    } else {
        e = new Cesium.Entity({
            id: track.label,
            wall: {
                //positions: new CustomProperty( Cesium.Cartesian3.fromDegreesArrayHeights( [track.lon, track.lat, track.alt])),
                positions: Cesium.Cartesian3.fromDegreesArrayHeights([track.lon, track.lat, track.alt]),
                material: Cesium.Color.fromAlpha(trackColor, 0.2),
                outline: true,
                outlineColor: Cesium.Color.fromAlpha(trackColor, 0.4),
                distanceDisplayCondition: trackPathDC
            }
        });

        trajectoryEntities.add(e);
    }
}

//--- track queries

const idQuery = /^ *id *= *(.+)$/;
// ..and more to follow

function getTrackEntryFilter(query) {
    let res = query.match(idQuery);
    if (res) {
        let idQuery = util.glob2regexp(res[1]);
        return (idQuery == '*') ? noTrackEntryFilter : te => te.id.match(idQuery);
    }
    return null;
}

function resetTrackEntryList() {
    trackEntryList.clear();
    trackEntries.forEach((te, id, map) => {
        if (trackEntryFilter(te)) trackEntryList.insert(te);
    });
    ui.setListItems(trackEntryView, trackEntryList);
}

function resetTrackEntryAssets() {
    trackEntries.forEach((te, id, map) => {
        let assets = te.assets;
        if (trackEntryFilter(te)) {
            let track = te.track;
            let pitch = track.pitch ? track.pitch : 0.0;
            let roll = track.roll ? track.roll : 0.0;
            let hpr = Cesium.HeadingPitchRoll.fromDegrees(track.hdg, pitch, roll);
            let pos = Cesium.Cartesian3.fromDegrees(track.lon, track.lat, track.alt);
            let attitude = Cesium.Transforms.headingPitchRollQuaternion(pos, hpr);

            if (!assets.symbol) {
                assets.symbol = createTrackSymbol(track, pos, attitude);
                // add info here
                addTrackSymbolEntity(assets.symbol);
            }
            if (!assets.info) {
                assets.info = createTrackInfo(track, pos);
                addTrackInfoEntity(assets.info);
            }
        } else { // filtered, check if we need to remove from viewer entities
            if (assets.symbol) {
                removeTrackSymbolEntity(assets.symbol);
                assets.symbol = null;
            }
            if (assets.info) {
                removeTrackInfoEntity(assets.info);
                assets.info = null;
            }
        }
    });
}

//--- interaction

app.queryTracks = function(event) {
    let input = ui.getFieldValue(event);

    let newFilter = getTrackEntryFilter(input);
    if (newFilter) {
        trackEntryFilter = newFilter;
        resetTrackEntryList();
        resetTrackEntryAssets();
    }
}

app.selectTrack = function(event) {
    let te = ui.getSelectedListItem(event);
    if (te) {
        if (te.assets.symbol) viewer.selectedEntity = te.assets.symbol;
    }
}

app.toggleShowPath = function(event) {
    let te = ui.getSelectedListItem(trackEntryView);
    if (te) {
        if (ui.toggleCheckbox(event)) {
            console.log("show path of " + te.id);
        } else {
            console.log("hide path of " + te.id);
        }
    }
}

app.setLinePath = function(event) {
    console.log("show line path");
}

app.setWallPath = function(event) {
    console.log("show wall path");
}

app.resetPaths = function() {
    console.log("reset path");
}

//--- view control

function setHomeView() {
    viewer.selectedEntity = undefined;
    viewer.trackedEntity = undefined;
    viewer.camera.flyTo({
        destination: Cesium.Cartesian3.fromDegrees(camera.lon, camera.lat, camera.alt),
        orientation: {
            heading: Cesium.Math.toRadians(0.0),
            pitch: Cesium.Math.toRadians(-90.0),
            roll: Cesium.Math.toRadians(0.0)
        }
    });
}
app.setHomeView = setHomeView;

function setDownView() {
    viewer.camera.flyTo({
        destination: viewer.camera.positionWC,
        orientation: {
            heading: Cesium.Math.toRadians(0.0),
            pitch: Cesium.Math.toRadians(-90.0),
            roll: Cesium.Math.toRadians(0.0)
        }
    });
}
app.setDownView = setDownView;


//--- Cesium CustomProperties

function CustomProperty(value) {
    this._value = value;
    this.isConstant = false;
    this.definitionChanged = new Cesium.Event();
}

CustomProperty.prototype.getValue = function(time, result) {
    return this._value;
};

CustomProperty.prototype.setValue = function(value) {
    this._value = value;
    this.definitionChanged.raiseEvent(this);
};
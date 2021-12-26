import * as config from "./config.js";
import { SkipList, CircularBuffer } from "./ui_data.js";
import * as util from "./ui_util.js";
import * as ui from "./ui.js";

const NO_PATH = "";
const LINE_PATH = "~";
const WALL_PATH = "≈";

class TrackAssets {
    constructor(symbol, info = null) {
        this.symbol = symbol;
        this.info = info;

        // on demand
        this.trajectoryPositions = []; // the value we use in the CallbackProperty for trajectory.positions (to avoid flicker) 
        this.trajectory = null;
    }
}

// object that wraps server-supplied track info with our locally kept trace and display assets
class TrackEntry {
    constructor(track, assets) {
        this.track = track;
        this.assets = assets;

        this.id = track.label;
        this.trace = new CircularBuffer(config.maxTraceLength);
    }

    assetDisplay() {
        let s = "";
        if (this.assets && this.assets.trajectory) {
            let tr = this.assets.trajectory;
            if (tr.polyline) s += LINE_PATH;
            else if (tr.wall) s += WALL_PATH;
            else s += NO_PATH;
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
    Cesium.Ion.defaultAccessToken = config.cesiumAccessToken;

    viewer = new Cesium.Viewer('cesiumContainer', {
        imageryProvider: config.imageryProvider,
        terrainProvider: config.terrainProvider,
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

    trajectoryDataSource = new Cesium.CustomDataSource('trajectories');
    viewer.dataSources.add(trajectoryDataSource);
    viewer.dataSources.lowerToBottom(trajectoryDataSource);

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

function addTrajectoryEntity(e) {
    //--- begin flicker hack: avoid flicker for single ConstantProperty positions update (CallbackProperty has position errors)
    let e0 = Object.assign({}, e);
    e0.id = e.id + "-0";
    if (e.wall) {
        e0.wall = e.wall.clone();
        e0.wall.positions = e.wall.positions.getValue().slice(0, 2);
    } else {
        e0.polyline = e.polyline.clone();
        e0.polyline.positions = e.polyline.positions.getValue().slice(0, 2);
    }
    trajectoryDataSource.entities.add(e0);
    //--- end flicker hack

    trajectoryDataSource.entities.add(e);
}

function removeTrajectoryEntity(e) {
    trajectoryDataSource.entities.removeById(e.id + "-0"); // flicker hack
    trajectoryDataSource.entities.remove(e);
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
        console.log("initializing websocket " + config.wsURL);
        ws = new WebSocket(config.wsURL);

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
            track.label = te.track.label; // don't store thousands of equal strings
            te.track = track;
            te.trace.push(track);

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

                if (te.assets.symbol) updateTrackSymbolAsset(te, pos, attitude);
                if (te.assets.info) updateTrackInfoAsset(te, pos);
                if (te.assets.trajectory) updateTrajectoryAsset(te);
            }
        }

    } else { // new one
        //console.log("add track: " + JSON.stringify(track));
        let assets = new TrackAssets(null, null, null);
        te = new TrackEntry(track, assets);
        trackEntries.set(te.id, te);
        te.trace.push(track);

        if (trackEntryFilter(te)) {
            assets.symbol = createTrackSymbolAsset(te, pos, attitude);
            assets.info = createTrackInfoAsset(te, pos);
            // trajectory only created on demand

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
function createTrackSymbolAsset(trackEntry, pos, attitude) {
    let track = trackEntry.track;

    let sym = new Cesium.Entity({
        id: track.label,
        position: pos,
        orientation: attitude,

        point: entityPrototype ? entityPrototype.point : {
            pixelSize: config.trackPointSize,
            color: config.trackColor,
            outlineColor: config.trackPointOutlineColor,
            outlineWidth: config.trackPointOutlineWidth,
            distanceDisplayCondition: config.trackPointDC
        },
        model: entityPrototype ? entityPrototype.model : {
            uri: config.trackModel,
            color: config.trackColor,
            //colorBlendMode: Cesium.ColorBlendMode.HIGHLIGHT,
            colorBlendMode: Cesium.ColorBlendMode.MIX,
            colorBlendAmount: 0.7,
            silhouetteColor: config.trackModelOutlineColor,
            silhouetteSize: config.trackModelOutlineWidth,
            minimumPixelSize: config.trackModelSize,
            distanceDisplayCondition: config.trackModelDC
        },
        label: {
            text: track.label,
            scale: 0.8,
            horizontalOrigin: Cesium.HorizontalOrigin.LEFT,
            font: config.trackLabelFont,
            fillColor: config.trackColor,
            showBackground: true,
            backgroundColor: config.trackLabelBackground, // alpha does not work against model
            outlineColor: config.trackColor,
            outlineWidth: 1,
            pixelOffset: config.trackLabelOffset,
            disableDepthTestDistance: config.minLabelDepth,
            distanceDisplayCondition: config.trackLabelDC
        }
        // track paths are separate entities
    });

    if (!entityPrototype) entityPrototype = sym; // first one

    return sym;
}

function updateTrackSymbolAsset(trackEntry, pos, attitude) {
    let sym = trackEntry.assets.symbol;
    sym.position = pos;
    sym.orientation = attitude;
}

function createTrackInfoAsset(trackEntry, pos) {
    return new Cesium.Entity({
        id: trackInfoLabel(trackEntry),
        position: pos,

        label: {
            font: config.trackInfoFont,
            scale: 0.8,
            horizontalOrigin: Cesium.HorizontalOrigin.LEFT,
            fillColor: config.trackColor,
            showBackground: true,
            backgroundColor: config.trackLabelBackground, // alpha does not work against model
            outlineColor: config.trackColor,
            outlineWidth: 1,
            pixelOffset: config.trackInfoOffset,
            disableDepthTestDistance: config.minLabelDepth,
            distanceDisplayCondition: config.trackInfoDC
        }
    });
}

function updateTrackInfoAsset(trackEntry, pos) {
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
    if (assets.trajectory) removeTrajectoryEntity(assets.trajectory);
}

function createTrajectoryAssetPositions(trackEntry) {
    let trace = trackEntry.trace;
    let positions = new Array(trace.size);
    let i = 0;
    trace.forEach(t => {
        positions[i++] = Cesium.Cartesian3.fromDegrees(t.lon, t.lat, t.alt);
    });
    return positions;
}

function createTrajectoryAsset(trackEntry, isWall) {
    // does not work for polylines (wrong endpoints) or walls (no show) if number of points is changing
    //let posCallback = new Cesium.CallbackProperty(() => { trackEntry.assets.trajectoryPositions }, false);

    if (isWall) {
        return new Cesium.Entity({
            id: trackEntry.id,
            wall: {
                positions: trackEntry.assets.trajectoryPositions, // posCallback,
                show: true,
                fill: true,
                material: Cesium.Color.fromAlpha(config.trackColor, 0.2),
                outline: true,
                outlineColor: Cesium.Color.fromAlpha(config.trackColor, 0.5),
                outlineWidth: config.trackPathWidth,
                distanceDisplayCondition: config.trackPathDC
            }
        });
    } else {
        return new Cesium.Entity({
            id: trackEntry.id,
            polyline: {
                positions: trackEntry.assets.trajectoryPositions, // posCallback,
                clampToGround: false,
                width: config.trackPathWidth,
                material: config.trackColor,
                distanceDisplayCondition: config.trackPathDC
            }
        });
    }
}

function updateTrajectoryAsset(trackEntry) {
    let positions = trackEntry.assets.trajectoryPositions;
    if (positions) {
        let track = trackEntry.track;
        positions.push(Cesium.Cartesian3.fromDegrees(track.lon, track.lat, track.alt));

        let entity = trackEntry.assets.trajectory.wall ? trackEntry.assets.trajectory.wall : trackEntry.assets.trajectory.polyline;
        entity.positions = positions; // this creates a new ConstantProperty and flickers
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
                assets.symbol = createTrackSymbolAsset(track, pos, attitude);
                // add info here
                addTrackSymbolEntity(assets.symbol);
            }
            if (!assets.info) {
                assets.info = createTrackInfoAsset(track, pos);
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
            if (assets.trajectory) {
                removeTrajectoryEntity(assets.trajectory);
                assets.trajectory = null;
                assets.trajectoryPositions = null; // don't do this before removing the asset
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
    let te = event.detail.curSelection;
    if (te) {
        if (te.assets.symbol) viewer.selectedEntity = te.assets.symbol;
        if (te.assets.trajectory) {
            ui.setCheckBox("console.tracks.path", true);
            if (te.assets.trajectory.wall) ui.selectRadio("console.tracks.wall");
            else ui.selectRadio("console.tracks.line");
        } else {
            ui.setCheckBox("console.tracks.path", false);
            ui.clearRadioGroup("console.tracks.line");
        }
    } else { // nothing selected
        ui.setCheckBox("console.tracks.path", false);
        ui.clearRadioGroup("console.tracks.line");
    }
}

app.toggleShowPath = function(event) {
    let te = ui.getSelectedListItem(trackEntryView);
    if (te) {
        if (ui.isCheckBoxSelected(event)) {
            let isWall = ui.isRadioSelected("console.tracks.wall");
            if (!isWall) ui.selectRadio("console.tracks.line");

            te.assets.trajectoryPositions = createTrajectoryAssetPositions(te);
            te.assets.trajectory = createTrajectoryAsset(te, isWall);
            addTrajectoryEntity(te.assets.trajectory);

        } else {
            if (te.assets.trajectory) {
                removeTrajectoryEntity(te.assets.trajectory);
                te.assets.trajectory = null;
                te.assets.trajectoryPositions = null;
                ui.clearRadioGroup("console.tracks.line");
            }
        }
    }
}

app.setLinePath = function(event) {
    let te = ui.getSelectedListItem(trackEntryView);
    if (te) {
        if (te.assets.trajectory) {
            if (te.assets.trajectory.wall) {
                removeTrajectoryEntity(te.assets.trajectory);
                te.assets.trajectory = createTrajectoryAsset(te, false);
                addTrajectoryEntity(te.assets.trajectory);
            }
        } else {
            te.assets.trajectoryPositions = createTrajectoryAssetPositions(te);
            te.assets.trajectory = createTrajectoryAsset(te, false);
            addTrajectoryEntity(te.assets.trajectory);
            ui.setCheckBox("console.tracks.path", true);
        }
    }
}

app.setWallPath = function(event) {
    let te = ui.getSelectedListItem(trackEntryView);
    if (te) {
        if (te.assets.trajectory) {
            if (te.assets.trajectory.polyline) {
                removeTrajectoryEntity(te.assets.trajectory);
                te.assets.trajectory = createTrajectoryAsset(te, true);
                addTrajectoryEntity(te.assets.trajectory);
            }
        } else {
            te.assets.trajectoryPositions = createTrajectoryAssetPositions(te);
            te.assets.trajectory = createTrajectoryAsset(te, true);
            addTrajectoryEntity(te.assets.trajectory);
            ui.setCheckBox("console.tracks.path", true);
        }
    }
}

app.resetPaths = function() {
    trackEntryList.forEach(te => {
        if (te.assets.trajectory) {
            removeTrajectoryEntity(te.assets.trajectory);
            te.assets.trajectory = null;
            te.assets.trajectoryPositions = null;
        }
    });
    ui.clearSelectedListItem(trackEntryView);
    ui.setCheckBox("console.tracks.path", false);
    ui.clearRadioGroup(ui.getRadio("console.tracks.wall"));
    viewer.selectedEntity = null;
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

app.toggleFullScreen = function(event) {
    ui.toggleFullScreen();
}

//--- Cesium CustomProperties

function DynamicProperty(value) {
    this.v = value;
    this.isConstant = false;
    this.definitionChanged = new Cesium.Event(); // create only one
}

DynamicProperty.prototype.getValue = function(time, result) {
    return this.v;
};

DynamicProperty.prototype.setValue = function(value) {
    this.v = value;
    this.definitionChanged.raiseEvent(this);
};
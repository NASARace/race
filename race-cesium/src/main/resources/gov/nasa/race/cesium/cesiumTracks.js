import { SkipList, CircularBuffer } from "./ui_data.js";
import * as util from "./ui_util.js";
import * as ui from "./ui.js";

const NO_PATH = "";
const LINE_PATH = "~";
const WALL_PATH = "≈";

// object that wraps server-supplied track info with our locally kept trace and display assets
class TrackEntry {
    constructor(track, assets) {
        this.track = track;
        this.assets = assets;

        this.id = track.id;
        this.trace = new CircularBuffer(maxTraceLength);
    }

    assetDisplay() {
        let s = "";
        if (assets) {
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

var trackInfoDataSource = undefined;
var trajectoryDataSource = undefined;

const trackEntries = new Map(); // id -> TrackEntry
const trackEntryList = new SkipList( // id-sorted list of track objects
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

    ui.setListItemDisplayColumns(view, ["fit"], [
        { name: "assets", width: "2rem", attrs: [], map: te => te.assetDisplay },
        { name: "id", width: "5rem", attrs: ["alignLeft"], map: te => te.id },
        { name: "date", width: "5rem", attrs: ["fixed"], map: te => util.toLocalTimeString(te.track.date) }
    ]);

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
    //viewer.scene.fxaa = false;
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
    let te = trackEntries.get(track.id);
    if (te) { // update
        console.log("update track: " + JSON.stringify(track));
        te.track = track;
        if (trackEntryFilter(te)) ui.updateListItem(trackEntryView, te);

    } else { // add
        console.log("add track: " + JSON.stringify(track));
        te = new TrackEntry(track, null);
        trackEntries.set(te.id, te);
        if (trackEntryFilter(te)) {
            trackEntryList.insert(te);
            ui.
        }
    }

    te.trace.push(track);
}

/*
function updateTrack(track) {
    // this can make use of the configured constants from config.js

    if (track.status == 4 || track.status == 8) {
        removeEntities(track);
        return;
    }

    let hdg = Cesium.Math.toRadians(track.hdg);
    let pitch = track.pitch ? track.pitch : 0.0;
    let roll = track.roll ? track.roll : 0.0;

    let pos = Cesium.Cartesian3.fromDegrees(track.lon, track.lat, track.alt);
    let hpr = Cesium.HeadingPitchRoll.fromDegrees(track.hdg, pitch, roll);
    let attitude = Cesium.Transforms.headingPitchRollQuaternion(pos, hpr);

    var e = viewer.entities.getById(track.label);
    if (e) { // just update position and attitude
        e.position = pos;
        e.orientation = attitude;

    } else { // add a new entry
        let e = createTrackEntity(track, pos, attitude)
        viewer.entities.add(e);
    }

    updateTrackInfo(track, pos);

    if (trackPathLength && trackPathLength > 0) {
        updateTrajectory(track);
    }
}
*/

var entityPrototype = undefined;

function createTrackEntity(track, pos, attitude) {
    let e = new Cesium.Entity({
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
            colorBlendAmount: 0.8,
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
            pixelOffset: trackLabelOffset,
            disableDepthTestDistance: minLabelDepth,
            distanceDisplayCondition: trackLabelDC
        }
        // track paths are separate entities
    });

    if (!entityPrototype) entityPrototype = e;

    return e;
}

function removeEntities(track) {
    let id = track.label;
    viewer.entities.removeById(id);
    trackInfoDataSource.entities.remove(id);
    trajectoryDataSource.entities.remove(id);
}

function updateTrackInfo(track, pos) {
    let fl = Math.round(track.alt * 0.00656167979) * 5;
    let hdg = Math.round(track.hdg);
    let spd = Math.round(track.spd * 1.94384449);

    let trackInfo = `FL${fl} ${hdg}° ${spd}kn`;
    let trackInfoEntities = trackInfoDataSource.entities;

    var e = trackInfoEntities.getById(track.label);
    if (e) {
        e.position = pos;
        e.label.text = trackInfo;

    } else {
        e = new Cesium.Entity({
            id: track.label,
            position: pos,

            label: {
                font: trackInfoFont,
                scale: 0.8,
                horizontalOrigin: Cesium.HorizontalOrigin.LEFT,
                fillColor: trackColor,
                showBackground: true,
                backgroundColor: trackLabelBackground, // alpha does not work against model
                pixelOffset: trackInfoOffset,
                disableDepthTestDistance: minLabelDepth,
                distanceDisplayCondition: trackInfoDC
            }
        });

        trackInfoEntities.add(e);
    }
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

//--- interaction

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


//--- track entry path display

app.toggleShowPath = function(event) {
    console.log("toggle show path");
    let isChecked = ui.toggleCheckbox(event);
    let te = ui.getSelectedListItem(trackEntryView);
}

app.setLinePath = function(event) {
    console.log("show line path");
}

app.setWallPath = function(event) {
    console.log("show wall path");
}

app.resetPath = function() {
    console.log("reset path");
}

//--- CustomProperties

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
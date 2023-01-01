import * as config from "./config.js";
import * as ui from "./ui.js";
import * as ws from "./ws.js";
import * as util from "./ui_util.js";

const UI_POSITIONS = "race-ui-positions";
const LOCAL = "local-";  // prefix for local position set names


class LayerEntry {
    constructor (wid,layerConfig,showAction) {
        this.id = layerConfig.name;    // (unique) full path: /cat/.../name

        let p = util.matchPath(this.id);
        this.name = p[2];
        this.category = p[1];

        this.config = layerConfig;     // at minimum {name,description,show}
        this.show = layerConfig.show;  // the configured initial state
        this.showAction = showAction   // module provided function to toggle visibility of assets

        this.modulePanelCb = undefined;
        this.layerOrderCb = undefined;
    }

    setVisible(showIt) {
        this.show = showIt;
        this.showAction(showIt);
        ui.setCheckBox(this.modulePanelCb,showIt); // in the module window
        ui.setCheckBox(this.layerOrderCb, showIt);
    }
}

// don't depend on member functions as we serialize/deserialize these

class PositionSet {
    constructor (name, positions) {
        this.name = name;
        this.positions = positions;
    }
}

class Position {
    constructor (name, latDeg, lonDeg, altM) {
        this.name = name;
        this.lat = latDeg;
        this.lon = lonDeg;
        this.alt = altM;

        this.asset = undefined; // on-demand point entity
    }
}

export var viewer = undefined;

var cameraSpec = undefined;
var lastCamera = undefined; // saved last position & orientation

var requestRenderMode = false;
var targetFrameRate = -1;

var pendingRenderRequest = false;

var layerOrder = []; // populated by initLayerPanel calls from modules
var layerOrderView = undefined; // showing the registered module layers
var layerHierarchy = [];
var layerHierarchyView = undefined;

var mouseMoveHandlers = [];
var mouseClickHandlers = [];

var homePosition = undefined;
var positionSets = [];
var selectedPositionSet = undefined;
var positions = undefined;
var positionsView = undefined;
var dataSource = undefined;
var showPointerLoc = true;

ui.registerLoadFunction(function initialize() {
    if (config.cesium.accessToken) Cesium.Ion.defaultAccessToken = config.cesium.accessToken;

    requestRenderMode = config.cesium.requestRenderMode;

    viewer = new Cesium.Viewer('cesiumContainer', {
        //terrainProvider: config.cesium.terrainProvider,
        skyBox: false,
        infoBox: false,
        baseLayerPicker: false,  // if true primitives don't work anymore ?? 
        sceneModePicker: true,
        navigationHelpButton: false,
        homeButton: false,
        timeline: false,
        animation: false,
        requestRenderMode: requestRenderMode
    });

    positionSets = getPositionSets();

    dataSource = new Cesium.CustomDataSource("positions");
    addDataSource(dataSource);

    setTargetFrameRate(config.cesium.targetFrameRate);
    initFrameRateSlider();

    if (requestRenderMode) ui.setCheckBox("view.rm", true);

    setCanvasSize();
    window.addEventListener('resize', setCanvasSize);

    viewer.resolutionScale = window.devicePixelRatio; // 2.0
    viewer.scene.fxaa = true;
    //viewer.scene.globe.depthTestAgainstTerrain=true;

    // event listeners
    viewer.camera.moveEnd.addEventListener(updateCamera);

    registerMouseMoveHandler(updateMouseLocation);
    viewer.scene.canvas.addEventListener('mousemove', handleMouseMove);
    viewer.scene.canvas.addEventListener('click', handleMouseClick);

    ws.addWsHandler(config.wsUrl, handleWsViewMessages);

    initCameraWindow();
    initLayerWindow();

    viewer.scene.postRender.addEventListener(function(scene, time) {
        pendingRenderRequest = false;
    });

    ui.registerPostLoadFunction(initModuleLayerViewData);
    ui.registerPostLoadFunction(setHomeView);  // finally set our home view

    console.log("ui_cesium initialized");
});

function initCameraWindow() {
    positionsView = initPositionsView();
    ui.selectRadio( showPointerLoc ? "view.showPointer" : "view.showCamera");
}

function initPositionsView() {
    let view = ui.getList("view.positions");
    if (view) {
        ui.setListItemDisplayColumns(view, ["fit", "header"], [
            { name: "", tip: "show/hide ground point", width: "2rem", attrs: [], map: e => ui.createCheckBox(e.asset, toggleShowPosition) },
            { name: "name", tip: "place name name", width: "4rem", attrs: [], map: e => e.name },
            { name: "lat", tip: "latitude [deg]", width:  "5.5rem", attrs: ["fixed", "alignRight"], map: e => util.formatFloat(e.lat,4)},
            { name: "lon", tip: "longitude [deg]", width:  "6.5rem", attrs: ["fixed", "alignRight"], map: e => util.formatFloat(e.lon,4)},
            { name: "alt", tip: "altitude [m]", width:  "5.2rem", attrs: ["fixed", "alignRight"], map: e => Math.round(e.alt)}
        ]);

        selectedPositionSet = positionSets[0];
        positions = selectedPositionSet.positions;
        ui.setChoiceItems("view.posSet", positionSets, 0);
        ui.setListItems(view, positions);
    }

    return view;
}

ui.exportToMain(function selectPositionSet(event) {
    let posSet = ui.getSelectedChoiceValue(event);
    if (posSet) {
        selectedPositionSet = posSet;
        positions = selectPositionSet.positions;
        ui.setListItems(positionsView, positions);
    }
});

function toggleShowPosition(event) {
    let cb = ui.getCheckBox(event.target);
    if (cb) {
        let pos = ui.getListItemOfElement(cb);
        if (pos) {
            if (ui.isCheckBoxSelected(cb)){
                if (!pos.asset) setPositionAsset(pos);
            } else {
                if (pos.asset) clearPositionAsset(pos);
            }
        }
    }
}

ui.exportToMain( function addPoint() {
    let latDeg = Number.parseFloat(ui.getFieldValue("view.latitude"));
    let lonDeg = Number.parseFloat(ui.getFieldValue("view.longitude"));
    let altM = Number.parseFloat(ui.getFieldValue("view.altitude"));

    if (isNaN(latDeg) || isNaN(lonDeg) || isNaN(altM)){
        alert("please enter valid latitude, longitude and altitude");
        return;
    }

    let name = prompt("please enter point name", positions.length.toString());
    if (name) {
        let pt = new Position(name, latDeg, lonDeg, altM);
        positions = util.copyArrayIfSame( selectedPositionSet.positions, positions);
        positions.push(pt);
        ui.setListItems(positionsView, positions);
    }
});

ui.exportToMain( function pickPoint() {
    let btn = ui.getButton("view.pickPos");
    ui.setElementColors( btn, ui.getRootVar("--selected-data-color"), ui.getRootVar("--selection-background"));

    // system prompt blocks DOM manipulation so we need to defer the action
    setTimeout( ()=> {
        let name = prompt("please enter point name and click on map", selectedPositionSet.positions.length);
        if (name) {
            pickSurfacePoint( (cp) => {
                if (cp) {
                    let latDeg = util.toDegrees(cp.latitude);
                    let lonDeg = util.toDegrees(cp.longitude);
                    let altM = ui.getFieldValue("view.altitude");
                    
                    ui.setField("view.latitude", latDeg);
                    ui.setField("view.longitude", lonDeg);
                    
                    let pt = new Position(name, latDeg, lonDeg, altM);
                    positions = util.copyArrayIfSame( selectedPositionSet.positions, positions);
                    positions.push(pt);
                    ui.setListItems("view.positions", positions);
                }
                ui.resetElementColors(btn);
            });
        } else {
            ui.resetElementColors(btn);
        }
    }, 100);
});

ui.exportToMain( function namePoint() {

});

ui.exportToMain( function removePoint() {
    let pos = ui.getSelectedListItem(positionsView);
    if (pos) {
        let idx = positions.findIndex( p=> p === pos);
        if (idx >= 0) {
            positions = util.copyArrayIfSame( selectedPositionSet.positions, positions);
            positions.splice(idx, 1);
            ui.setListItems(positionsView, positions);
        }
    }
});

function getPositionSets() {
    let sets = [];
    sets.push( getGlobalPositionSet());
    getLocalPositionSets().forEach( ps=> sets.push(ps));
    return sets;
}

// TODO - we should support multiple gobal position sets
function getGlobalPositionSet() { // from config
    let positions = config.cesium.cameraPositions.map( p=> new Position(p.name, p.lat, p.lon, p.alt));
    let pset = new PositionSet("default", positions);

    homePosition = positions.find( p=> p.name === "home");
    if (!homePosition) homePosition = positions[0];

    return pset;
}

function getLocalPositionSets() { // from local storage
    let psets = localStorage.getItem(UI_POSITIONS);
    return psets ? JSON.parse(psets) : [];
}

ui.exportToMain(function selectPositionSet(event) {
    let ps = ui.getSelectedChoiceValue(event);
    if (ps) {
        selectedPositionSet = ps;
        positions = ps.positions;
        ui.setListItems(positionsView, positions);
    }
});

function filterAssets(k,v) {
    if (k === 'asset') return undefined;
    else return v;
}

ui.exportToMain(function storePositionSet() {
    if (selectedPositionSet) {
        let psName = selectedPositionSet.name;
        if (!psName.startsWith(LOCAL)) psName = LOCAL + psName;

        psName = prompt("please enter name for local poisition set",psName);
        if (psName) {
            if (!psName.startsWith(LOCAL)) psName = LOCAL + psName;

            let newPss = getLocalPositionSets();
            let newPs = new PositionSet(psName, positions);
            let idx = newPss.findIndex(e => e.name === psName);
            if (idx <0 ) {
                newPss.push( newPs);
                idx = newPss.length-1;
            } else {
                newPss[idx] = newPs;
            }

            localStorage.setItem(UI_POSITIONS, JSON.stringify( newPss, filterAssets));

            newPss.unshift(getGlobalPositionSet());
            selectedPositionSet = newPs;
            positions = selectedPositionSet.positions;
            ui.setChoiceItems("view.posSet", newPss, idx+1);
            ui.selectChoiceItem("view.posSet", newPs);
            ui.setListItems(positionsView, positions);
        }
    }
});

ui.exportToMain(function removePositionSet() {
    if (selectedPositionSet) {
        let psName = selectedPositionSet.name;
        if (!psName.startsWith(LOCAL)) {
            alert("denied - cannot remove non-local position sets");
            return;
        }

        let localPs = getLocalPositionSets();
        let idx = localPs.findIndex(e => e.name === psName);
        if (idx >= 0){
            if (confirm("delete position set " + selectedPositionSet.name)) {
                if (localPs.length == 1) {
                    localStorage.removeItem(UI_POSITIONS);
                } else {
                    localPs.splice(idx, 1);
                    localStorage.setItem(UI_POSITIONS, JSON.stringify(localPs));
                }

                let ps = getPositionSets();
                selectedPositionSet = ps[0];
                positions = selectedPositionSet.positions;
                ui.setChoiceItems("view.posSet", ps, 0);
                ui.setListItems(positionsView, positions);
            }
        }
    }
});


function setPositionAsset(pos) {
    let cfg = config.cesium;

    let e = new Cesium.Entity({
        id: pos.name,
        position: Cesium.Cartesian3.fromDegrees( pos.lon, pos.lat),
        point: {
            pixelSize: cfg.pointSize,
            color: cfg.color,
            outlineColor: cfg.outlineColor,
            outlineWidth: 1,
            disableDepthTestDistance: Number.NEGATIVE_INFINITY
        },
        label: {
            text: pos.name,
            font: cfg.font,
            fillColor: cfg.outlineColor,
            showBackground: true,
            backgroundColor: cfg.labelBackground,
            //heightReference: Cesium.HeightReference.CLAMP_TO_GROUND,
            horizontalOrigin: Cesium.HorizontalOrigin.LEFT,
            verticalOrigin: Cesium.VerticalOrigin.TOP,
            pixelOffset: new Cesium.Cartesian2( 5, 5)
        }
    });
    pos.asset = e;
    dataSource.entities.add(e);
    requestRender();
}

function clearPositionAsset(pos) {
    if (pos.asset) {
        dataSource.entities.remove(pos.asset);
        pos.asset = undefined;
        requestRender();
    }
}

ui.exportToMain( function showPointer(){
    showPointerLoc = true;
});

ui.exportToMain( function showCamera(){
    showPointerLoc = false;
});


function initFrameRateSlider() {
    let e = ui.getSlider('view.fr');
    if (e) {
        ui.setSliderRange(e, 0.0, 60, 10, util.f_0);
        ui.setSliderValue(e, targetFrameRate);
    }
}

function setTargetFrameRate(fr) {
    targetFrameRate = fr;
    if (fr > 0) {
        viewer.targetFrameRate = targetFrameRate;
    } else {
        viewer.targetFrameRate = undefined; // whatever the browser default animation rate is
    }
}

export function lowerFrameRateWhile(action, lowFr) {
    viewer.targetFrameRate = lowFr;
    action();
    viewer.targetFrameRate = targetFrameRate;
}

export function lowerFrameRateFor(msec, lowFr) {
    let curFr = viewer.targetFrameRate;
    viewer.targetFrameRate = lowFr;
    setTimeout(() => {
        viewer.targetFrameRate = curFr;
        requestRender();
    }, msec);
}

export function setRequestRenderMode(cond) {
    requestRenderMode = cond;
    viewer.scene.requestRenderMode = cond;
    ui.setCheckBox("view.rm", cond);
}

export function isRequestRenderMode() {
    return requestRenderMode;
}

export function requestRender() {
    if (requestRenderMode && !pendingRenderRequest) {
        pendingRenderRequest = true;
        viewer.scene.requestRender();
    }
}

export function withSampledTerrain(positions, level, action) {
    const promise = Cesium.sampleTerrain(viewer.terrainProvider, level, positions);
    Promise.resolve(promise).then(action);
}

export function withDetailedSampledTerrain(positions, action) {
    const promise = Cesium.sampleTerrainMostDetailed(viewer.terrainProvider, positions);
    Promise.resolve(promise).then(action);
}

export function createScreenSpaceEventHandler() {
    return new Cesium.ScreenSpaceEventHandler(viewer.scene.canvas);
}

export function setCursor(cssCursorSpec) {
    viewer.scene.canvas.style.cursor = cssCursorSpec;
}

export function setDefaultCursor() {
    viewer.scene.canvas.style.cursor = "default";
}

function setCanvasSize() {
    viewer.canvas.width = window.innerWidth;
    viewer.canvas.height = window.innerHeight;
}

export function setDoubleClickHandler (action) {
    let selHandler = new Cesium.ScreenSpaceEventHandler(viewer.scene.canvas);
    selHandler.setInputAction(action, Cesium.ScreenSpaceEventType.LEFT_DOUBLE_CLICK);
}

export function setEntitySelectionHandler(onSelect) {
    let selHandler = new Cesium.ScreenSpaceEventHandler(viewer.scene.canvas);
    selHandler.setInputAction(onSelect, Cesium.ScreenSpaceEventType.LEFT_CLICK);
}

export function addDataSource(dataSrc) {
    viewer.dataSources.add(dataSrc);
}

export function removeDataSource(dataSrc) {
    viewer.dataSources.remove(dataSrc);
}

export function toggleDataSource(dataSrc) {
    if (viewer.dataSources.contains(dataSrc)) {
        viewer.dataSources.remove(dataSrc);
    } else {
        viewer.dataSources.add(dataSrc);
    }
}

export function isDataSourceShowing(dataSrc) {
    return viewer.dataSources.contains(dataSrc);
}

export function addPrimitive(prim) {
    viewer.scene.primitives.add(prim);
}

export function removePrimitive(prim) {
    viewer.scene.primitives.remove(prim);
}

export function clearSelectedEntity() {
    viewer.selectedEntity = null;
}

export function getSelectedEntity() {
    return viewer.selectedEntity;
}

export function setSelectedEntity(e) {
    viewer.selectedEntity = e;
}

export function addEntity(e) {
    viewer.entities.add(e);
}
export function removeEntity(e) {
    viewer.entities.remove(e);
}

//--- generic 'view' window of the UI

function handleWsViewMessages(msgType, msg) {
    switch (msgType) {
        case "camera":
            handleCameraMessage(msg.camera);
            return true;
        case "setClock":
            handleSetClock(msg.setClock);
            return true;
        default:
            return false;
    }
}

//--- websock handler funcs

function handleCameraMessage(newCamera) {
    cameraSpec = newCamera;
    setHomeView();
}

function handleSetClock(setClock) {
    ui.setClock("time.utc", setClock.time, setClock.timeScale, true);
    ui.setClock("time.loc", setClock.time, setClock.timeScale);
    ui.resetTimer("time.elapsed", setClock.timeScale);
    ui.startTime();
}

function updateCamera() {
    let pos = viewer.camera.positionCartographic;
    ui.setField("view.altitude", Math.round(pos.height).toString());
    //saveCamera();
}

//--- mouse event handlers

export function registerMouseMoveHandler(handler) {
    mouseMoveHandlers.push(handler);
}

export function releaseMouseMoveHandler(handler) {
    let idx = mouseMoveHandlers.findIndex(h => h === handler);
    if (idx >= 0) mouseMoveHandlers.splice(idx,1);
}

export function registerMouseClickHandler(handler) {
    mouseClickHandlers.push(handler);
}

export function releaseMouseClickHandler(handler) {
    let idx = mouseClickHandlers.findIndex(h => h === handler);
    if (idx >= 0) mouseClickHandlers.splice(idx,1);
}

function handleMouseMove(e) {
    mouseMoveHandlers.forEach( handler=> handler(e));
}

function handleMouseClick(e) {
    mouseClickHandlers.forEach( handler=> handler(e));
}

function getCartographicMousePosition(e) {
    var ellipsoid = viewer.scene.globe.ellipsoid;
    var cartesian = viewer.camera.pickEllipsoid(new Cesium.Cartesian3(e.clientX, e.clientY), ellipsoid);
    if (cartesian) {
        return ellipsoid.cartesianToCartographic(cartesian);
    } else {
        return undefined;
    }
}

function updateMouseLocation(e) {
    if (showPointerLoc) {
        let pos = getCartographicMousePosition(e)
        if (pos) {
            let longitudeString = Cesium.Math.toDegrees(pos.longitude).toFixed(4);
            let latitudeString = Cesium.Math.toDegrees(pos.latitude).toFixed(4);

            ui.setField("view.latitude", latitudeString);
            ui.setField("view.longitude", longitudeString);
        }
    }
}

//--- user control 

function setViewFromFields() {
    let lat = ui.getFieldValue("view.latitude");
    let lon = ui.getFieldValue("view.longitude");
    let alt = ui.getFieldValue("view.altitude");

    if (lat && lon && alt) {
        let latDeg = parseFloat(lat);
        let lonDeg = parseFloat(lon);
        let altM = parseFloat(alt);

        // TODO - we should check for valid ranges here
        if (isNaN(latDeg)) { alert("invalid latitude: " + lat); return; }
        if (isNaN(lonDeg)) { alert("invalid longitude: " + lon); return; }
        if (isNaN(altM)) { alert("invalid altitude: " + alt); return; }

        viewer.camera.flyTo({
            destination: Cesium.Cartesian3.fromDegrees(lonDeg, latDeg, altM),
            orientation: centerOrientation
        });
    } else {
        alert("please enter latitude, longitude and altitude");
    }
}
ui.exportToMain(setViewFromFields);

export function saveCamera() {
    let camera = viewer.camera;
    let pos = camera.positionCartographic

    lastCamera = {
        lat: util.toDegrees(pos.latitude),
        lon: util.toDegrees(pos.longitude),
        alt: pos.height,
        heading: util.toDegrees(camera.heading),
        pitch: util.toDegrees(camera.pitch),
        roll: util.toDegrees(camera.roll)
    };

    let spec = `{ lat: ${util.fmax_4.format(lastCamera.lat)}, lon: ${util.fmax_4.format(lastCamera.lon)}, alt: ${Math.round(lastCamera.alt)} }`;
    //navigator.clipboard.writeText(spec);  // this is still experimental in browsers and needs to be enabled explicitly for sec reasons
    console.log(spec);
}
ui.exportToMain(saveCamera);

const centerOrientation = {
    heading: Cesium.Math.toRadians(0.0),
    pitch: Cesium.Math.toRadians(-90.0),
    roll: Cesium.Math.toRadians(0.0)
}

export function zoomTo(cameraPos) {
    saveCamera()

    viewer.camera.flyTo({
        destination: cameraPos,
        orientation: centerOrientation
    });
}

export function setHomeView() {
    setCamera(homePosition);
}
ui.exportToMain(setHomeView);

export function setCamera(camera) {
    saveCamera();

    viewer.selectedEntity = undefined;
    viewer.trackedEntity = undefined;
    viewer.camera.flyTo({
        destination: Cesium.Cartesian3.fromDegrees(camera.lon, camera.lat, camera.alt),
        orientation: centerOrientation
    });
}

ui.exportToMain( function setCameraFromSelection(event){
    let places = ui.getList(event);
    if (places) {
        let cp = ui.getSelectedListItem(places);
        if (cp) {
            setCamera(cp);
        }
    }
})

var minCameraHeight = 50000;

export function setDownView() {

    // use the position we are looking at, not the current camera position
    const canvas = viewer.scene.canvas;
    const center = new Cesium.Cartesian2(canvas.clientWidth / 2.0, canvas.clientHeight / 2.0);
    const ellipsoid = viewer.scene.globe.ellipsoid;
    let wc = viewer.camera.pickEllipsoid(center,ellipsoid);
    let pos = Cesium.Cartographic.fromCartesian(wc);

    //let pos = viewer.camera.positionCartographic;
    if (pos.height < minCameraHeight) pos = new Cesium.Cartographic(pos.longitude,pos.latitude,minCameraHeight);

    viewer.trackedEntity = undefined;

    viewer.camera.flyTo({
        destination: Cesium.Cartographic.toCartesian(pos),
        orientation: centerOrientation
    });
}
ui.exportToMain(setDownView);

export function restoreCamera() {
    if (lastCamera) {
        let last = lastCamera;
        saveCamera();
        setCamera(last);
    }
}
ui.exportToMain(restoreCamera);


export function toggleFullScreen(event) {
    ui.toggleFullScreen();
}
ui.exportToMain(toggleFullScreen);

ui.exportToMain(function toggleRequestRenderMode() {
    requestRenderMode = !requestRenderMode;
    viewer.scene.requestRenderMode = requestRenderMode;
});

ui.exportToMain(function setFrameRate(event) {
    let v = ui.getSliderValue(event.target);
    setTargetFrameRate(v);
});

//--- layer panel init

function initLayerWindow() {
    layerOrderView = initLayerOrderView();
    layerHierarchyView = initLayerHierarchyView();
}

function initLayerOrderView() {
    let v = ui.getList("layer.order");
    if (v) {
        ui.setListItemDisplayColumns(v, ["fit", "header"], [
            { name: "", width: "2rem", attrs: [], map: e =>  setLayerOrderCb(e) },
            { name: "name", width: "8rem", attrs: [], map: e => e.name },
            { name: "cat", width: "10rem", attrs: [], map: e => e.category}
        ]);
    }
    return v;
}

function setLayerOrderCb(le) {
    let cb = ui.createCheckBox(le.show, toggleShowLayer);
    le.layerOrderCb = cb;
    return cb;
}

function initLayerHierarchyView() {
    let v = ui.getList("layer.hierarchy");
    if (v) {

    }
    return v;
}

function toggleShowLayer(event) {
    let cb = ui.getCheckBox(event.target);
    if (cb) {
        let le = ui.getListItemOfElement(cb);
        if (le) le.setVisible(ui.isCheckBoxSelected(cb));
    }
}

// called after all modules have loaded
function initModuleLayerViewData() {
    ui.setListItems(layerOrderView, layerOrder);
}

// called by layer modules during their init - the panel is in the respective module window
export function initLayerPanel(wid, conf, showAction) {
    if (conf && conf.layer) {
        let phe = document.getElementById(wid + ".layer-header")
        if (phe) {
            let le = new LayerEntry(wid,conf.layer,showAction);

            phe.innerText = "layer: " + conf.layer.name.replaceAll('/', '╱'); // │
            let cb = ui.createCheckBox(conf.layer.show, (event) => {
                event.stopPropagation();
                le.setVisible(ui.isCheckBoxSelected(cb));
            });
            ui.positionRight(cb, 0);
            phe.appendChild(cb);
            le.modulePanelCb = cb;

            ui.setLabelText(wid + '.layer-descr', conf.layer.description);

            layerOrder.push(le);
        }
    }
}

export function isLayerShowing(layerPath) {
    let le = layerOrder.find( le=> le.id == layerPath)
    return (le && le.show);
}

ui.exportToMain(function raiseModuleLayer(event){
    let le = ui.getSelectedListItem(layerOrderView);
    console.log("TBD raise layer: " + le);
});

ui.exportToMain(function lowerModuleLayer(event){
    let le = ui.getSelectedListItem(layerOrderView);
    console.log("TBD lower layer: " + le);
});

//--- interactive geo input

export function pickSurfacePoint (callback) {
    let cancel = false;

    function onKeydown(event) {
        if (event.key == "Escape") {
            cancel = true;
            viewer.scene.canvas.click();
        }
    }

    function onClick(event) {
        let p = getCartographicMousePosition(event);
        if (p) { 
            callback( cancel ? null : p)
        }
        setDefaultCursor();
        releaseMouseClickHandler(onClick);
        document.removeEventListener( 'keydown', onKeydown);
    }

    document.addEventListener('keydown', onKeydown);
    setCursor("crosshair");
    registerMouseClickHandler(onClick);
}

// this should normally use a ScreenSpaceEventHandler but that fails for some reason if
// sceneModePicker is enabled (positions are off). This one does not correctly handle terrain but is close enough
export function pickSurfaceRectangle (callback) {
    var asset = undefined;
    var p0 = undefined;
    var rect = undefined;
    let poly = Cesium.Cartesian3.fromDegreesArray([0,0, 0,0, 0,0, 0,0, 0,0]);

    function onMouseMove(event) {
        let p = getCartographicMousePosition(event);
        if (p) {
            rect.west = Math.min( p0.longitude, p.longitude);
            rect.south = Math.min( p0.latitude, p.latitude);
            rect.east = Math.max( p0.longitude, p.longitude);
            rect.north = Math.max( p0.latitude, p.latitude);
            // FIXME - we can do better than to convert back to where we came from. just rotate
            cartesian3ArrayFromRadiansRect(rect, poly);
        }
        requestRender();
    }

    function onClick(event) {
        let p = getCartographicMousePosition(event);
        if (p) { 
            if (!rect) {
                p0 = p;
                rect = new Cesium.Rectangle(p0.longitude, p0.latitude, p0.longitude, p0.latitude);

                asset = new Cesium.Entity({
                    polyline: {
                        positions: new Cesium.CallbackProperty( () => poly, false),
                        clampToGround: true,
                        width: 2,
                        material: Cesium.Color.RED
                    },
                    selectable: false
                });
                viewer.entities.add(asset);
                requestRender();

                registerMouseMoveHandler(onMouseMove);

            } else {
                setDefaultCursor();
                releaseMouseMoveHandler(onMouseMove);
                releaseMouseClickHandler(onClick);
                viewer.entities.remove(asset);

                rect.west = Cesium.Math.toDegrees(rect.west);
                rect.south = Cesium.Math.toDegrees(rect.south);
                rect.east = Cesium.Math.toDegrees(rect.east);
                rect.north = Cesium.Math.toDegrees(rect.north);

                callback(rect);
                requestRender();
            }
        }
    }

    setCursor("crosshair");
    registerMouseClickHandler(onClick);
}


export function cartesian3ArrayFromRadiansRect (rect, arr=null) {
    let a = arr ? arr : new Array(5);

    a[0] = Cesium.Cartesian3.fromRadians( rect.west, rect.north);
    a[1] = Cesium.Cartesian3.fromRadians( rect.east, rect.north);
    a[2] = Cesium.Cartesian3.fromRadians( rect.east, rect.south);
    a[3] = Cesium.Cartesian3.fromRadians( rect.west, rect.south);
    a[4] = Cesium.Cartesian3.fromRadians( rect.west, rect.north);

    return a;
}

export function cartesian3ArrayFromDegreesRect (rect, arr=null) {
    let a = arr ? arr : new Array(5);

    a[0] = Cesium.Cartesian3.fromDegrees( rect.west, rect.north);
    a[1] = Cesium.Cartesian3.fromDegrees( rect.east, rect.north);
    a[2] = Cesium.Cartesian3.fromDegrees( rect.east, rect.south);
    a[3] = Cesium.Cartesian3.fromDegrees( rect.west, rect.south);
    a[4] = Cesium.Cartesian3.fromDegrees( rect.west, rect.north);

    return a;
}

export function withinRect(latDeg, lonDeg, degRect) {
    return (lonDeg >= degRect.west) && (lonDeg <= degRect.east) && (latDeg >= degRect.south) && (latDeg <= degRect.north);
}
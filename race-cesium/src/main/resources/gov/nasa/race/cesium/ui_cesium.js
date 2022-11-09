import * as config from "./config.js";
import * as ui from "./ui.js";
import * as ws from "./ws.js";
import * as util from "./ui_util.js";

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

export var viewer = undefined;

var cameraSpec = undefined;

var requestRenderMode = false;
var targetFrameRate = -1;

var imageryLayers = config.cesium.imageryLayers;
var imageryParams = {...config.cesium.imageryParams }; // we might still adjust them based on theme (hence we have to copy)

var pendingRenderRequest = false;

var layerOrder = []; // populated by initLayerPanel calls from modules
var layerOrderView = undefined; // showing the registered module layers
var layerHierarchy = [];
var layerHierarchyView = undefined;

var mouseMoveHandlers = [];
var mouseClickHandlers = [];

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

    initLayerWindow();

    viewer.scene.postRender.addEventListener(function(scene, time) {
        pendingRenderRequest = false;
    });

    ui.registerPostLoadFunction(initModuleLayerViewData);
    console.log("ui_cesium initialized");
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
    let pos = getCartographicMousePosition(e)
    if (pos) {
        let longitudeString = Cesium.Math.toDegrees(pos.longitude).toFixed(5);
        let latitudeString = Cesium.Math.toDegrees(pos.latitude).toFixed(5);

        ui.setField("view.latitude", latitudeString);
        ui.setField("view.longitude", longitudeString);
    }
}

//--- user control 

export function zoomTo(cameraPos) {
    viewer.camera.flyTo({
        destination: cameraPos,
        orientation: {
            heading: Cesium.Math.toRadians(0.0),
            pitch: Cesium.Math.toRadians(-90.0),
            roll: Cesium.Math.toRadians(0.0)
        }
    });
}

export function setHomeView() {
    viewer.selectedEntity = undefined;
    viewer.trackedEntity = undefined;
    viewer.camera.flyTo({
        destination: Cesium.Cartesian3.fromDegrees(cameraSpec.lon, cameraSpec.lat, cameraSpec.alt),
        orientation: {
            heading: Cesium.Math.toRadians(0.0),
            pitch: Cesium.Math.toRadians(-90.0),
            roll: Cesium.Math.toRadians(0.0)
        }
    });
}
ui.exportToMain(setHomeView);

export function setDownView() {
    viewer.camera.flyTo({
        destination: viewer.camera.positionWC,
        orientation: {
            heading: Cesium.Math.toRadians(0.0),
            pitch: Cesium.Math.toRadians(-90.0),
            roll: Cesium.Math.toRadians(0.0)
        }
    });
}
ui.exportToMain(setDownView);

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
import * as config from "./config.js";
import * as ui from "./ui.js";
import * as ws from "./ws.js";
import * as util from "./ui_util.js";

export var viewer = undefined;
export var camera = undefined;

export function initialize() {
    Cesium.Ion.defaultAccessToken = config.cesiumAccessToken;

    viewer = new Cesium.Viewer('cesiumContainer', {
        imageryProvider: config.imageryProvider, //config.imageryProviders[0].provider,
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

    // event listeners
    viewer.camera.moveEnd.addEventListener(updateCamera);
    viewer.scene.canvas.addEventListener('mousemove', updateMouseLocation);

    ws.addWsHandler(handleWsViewMessages);

    initViewWindow();

    return true;
}

function initImageryLayers() {

}

function initViewWindow() {
    initMapParameters()
}

function initMapParameters() {
    let e = ui.getSlider('view.map.brightness');
    ui.setSliderRange(e, 0, 2.0, 0.1, util.f_1);
    ui.setSliderValue(e, config.imageryProviderOptions.defaultBrightness);

    e = ui.getSlider('view.map.contrast');
    ui.setSliderRange(e, 0, 2.0, 0.1, util.f_1);
    ui.setSliderValue(e, config.imageryProviderOptions.defaultContrast);

    e = ui.getSlider('view.map.hue');
    ui.setSliderRange(e, 0, 1.0, 0.1, util.f_1);
    ui.setSliderValue(e, config.imageryProviderOptions.defaultHue);

    e = ui.getSlider('view.map.saturation');
    ui.setSliderRange(e, 0, 2.0, 0.1, util.f_1);
    ui.setSliderValue(e, config.imageryProviderOptions.defaultSaturation);

    e = ui.getSlider('view.map.gamma');
    ui.setSliderRange(e, 0, 2.0, 0.1, util.f_1);
    ui.setSliderValue(e, config.imageryProviderOptions.defaultGamma);

    e = ui.getSlider('view.map.alpha');
    ui.setSliderRange(e, 0, 1.0, 0.1, util.f_1);
    ui.setSliderValue(e, config.imageryProviderOptions.defaultAlpha);
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

export function clearSelectedEntity() {
    viewer.selectedEntity = null;
}

export function getSelectedEntity() {
    return viewer.selectedEntity;
}

export function setSelectedEntity(e) {
    viewer.selectedEntity = e;
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
    camera = newCamera;
    setHomeView();
}

function handleSetClock(setClock) {
    ui.setClock("time.utc", setClock.time, setClock.timeScale);
    ui.setClock("time.loc", setClock.time, setClock.timeScale);
    ui.resetTimer("time.elapsed", setClock.timeScale);
    ui.startTime();
}

function updateCamera() {
    let pos = viewer.camera.positionCartographic;
    ui.setField("view.altitude", Math.round(pos.height).toString());
}

function updateMouseLocation(e) {
    var ellipsoid = viewer.scene.globe.ellipsoid;
    var cartesian = viewer.camera.pickEllipsoid(new Cesium.Cartesian3(e.clientX, e.clientY), ellipsoid);
    if (cartesian) {
        let cartographic = ellipsoid.cartesianToCartographic(cartesian);
        let longitudeString = Cesium.Math.toDegrees(cartographic.longitude).toFixed(5);
        let latitudeString = Cesium.Math.toDegrees(cartographic.latitude).toFixed(5);

        ui.setField("view.latitude", latitudeString);
        ui.setField("view.longitude", longitudeString);
    }
}


//--- user control 

export function setHomeView() {
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

function setImageryProvider(event, providerName) {
    let p = config.imageryProviders.find(p => p.name == providerName);
    if (p) {
        console.log("switching to " + p.name);
        viewer.imageryProvider = p.provider;
    } else {
        console.log("unknown imagery provider: " + providerName);
    }
}
ui.exportToMain(setImageryProvider);

function toggleOverlayProvider(event, providerName) {
    let provider = config.overlayProviders.find(p => p.name == providerName);
    if (provider) {
        console.log("toggle overlay" + providerName);
    } else {
        console.log("unknown overlay provider: " + providerName);
    }
}
ui.exportToMain(toggleOverlayProvider);


//--- map rendering parameters

ui.exportToMain(function setMapBrightness(event) {
    let v = ui.getSliderValue(event.target);
    viewer.imageryLayers.get(0).brightness = v;
});

ui.exportToMain(function setMapContrast(event) {
    let v = ui.getSliderValue(event.target);
    viewer.imageryLayers.get(0).contrast = v;
});

ui.exportToMain(function setMapHue(event) {
    let v = ui.getSliderValue(event.target);
    viewer.imageryLayers.get(0).hue = v;
});

ui.exportToMain(function setMapSaturation(event) {
    let v = ui.getSliderValue(event.target);
    viewer.imageryLayers.get(0).saturation = v;
});

ui.exportToMain(function setMapGamma(event) {
    let v = ui.getSliderValue(event.target);
    viewer.imageryLayers.get(0).gamma = v;
});

ui.exportToMain(function setMapAlpha(event) {
    let v = ui.getSliderValue(event.target);
    viewer.imageryLayers.get(0).alpha = v;
});
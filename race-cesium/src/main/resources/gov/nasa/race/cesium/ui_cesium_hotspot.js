import * as config from "./config.js";
import * as ws from "./ws.js";
import * as util from "./ui_util.js";
import * as ui from "./ui.js";
import * as uiCesium from "./ui_cesium.js";

class HotspotEntry {
    constructor(hs) {
        this.hotspots = hs;
        this.date = hs[0].date;
    }
}

var hotspotEntries = [];
var hotspotView = undefined;
var dataSource = undefined;
var timeSteps = undefined;
var brightThreshold = undefined;
var frpThreshold = undefined;

ui.registerLoadFunction(function initialize() {
    hotspotView = initHotspotView();
    initHotspotSliders();
    ws.addWsHandler(config.wsUrl, handleWsHotspotMessages);

    dataSource = new Cesium.CustomDataSource("hotspots");
    uiCesium.addDataSource(dataSource);

    timeSteps = config.hotspot.timeSteps;
    brightThreshold = config.hotspot.bright;
    frpThreshold = config.hotspot.frp;

    console.log("ui_cesium_hotspot initialized");
});

function initHotspotView() {
    let view = ui.getList("hotspot.list");
    if (view) {
        ui.setListItemDisplayColumns(view, ["fit"], [
            { name: "size", width: "4rem", attrs: ["fixed", "alignRight"], map: e => e.hotspots.length },
            { name: "date", width: "13rem", attrs: ["fixed", "alignRight"], map: e => util.toLocalDateTimeString(e.date) }
        ]);
    }
    return view;
}

function initHotspotSliders() {
    let e = ui.getSlider('hotspot.history');
    ui.setSliderRange(e, 0, 7, 1, util.f_0);
    ui.setSliderValue(e, config.hotspot.history);
}


function handleWsHotspotMessages(msgType, msg) {
    switch (msgType) {
        case "hotspots":
            handleHotspotsMessage(msg.hotspots);
            return true;
        case "dropHotspots":
            handleDropHotspotsMessage(msg.dropHotspots);
            return true;
        default:
            return false;
    }
}

function handleHotspotsMessage(hotspots) {
    hotspots.forEach(h => {
        h.id = util.toLatLonString(h.lat, h.lon, 4);
    });

    let hsEntry = new HotspotEntry(hotspots);
    addHotspotEntry(hsEntry);
}

function handleDropHotspotsMessage(date) {
    console.log("drop hotspots: " + date);
}

// we add the latest on top
function addHotspotEntry(e) {
    let d = e.date;
    for (let i = 0; i < hotspotEntries.length; i++) {
        if (d >= hotspotEntries[i].date) {
            hotspotEntries.splice(i, 0, e);
            ui.insertListItem(hotspotView, e, i);
            return;
        }
    }

    hotspotEntries.push(e);
    ui.appendListItem(hotspotView, e);
}

function getCutoffTime(hsIdx) {
    let dateCut = 0;
    let dt = config.hotspot.history * 86400000; // history is configured as days, hotspot date is msec

    let utcClock = ui.getClock("time.utc");
    if (utcClock) { // if we have a UTC clock use current clock time as basis for cutoff
        return ui.getClockEpochMillis(utcClock) - dt;

    } else {
        let e = hotspotEntries[hsIdx];
        return e.date - dt;
    }
}

function computeHotspotPixels(hsIdx) {
    let pixels = [];
    let seen = new Set();
    let cutoff = getCutoffTime(hsIdx);

    for (var i = hsIdx; i < hotspotEntries.length; i++) { // hotspot entries are ordered latest-first
        let e = hotspotEntries[i];
        if (e.date > cutoff) {
            let hs = e.hotspots;
            for (var j = 0; j < hs.length; j++) {
                let h = hs[j];
                let k = h.id;
                if (!seen.has(k)) {
                    seen.add(k);
                    pixels.push(h);
                }
            }
        } else {
            break;
        }
    }

    return pixels;
}

function createPixelAssets(pixels) {
    let refDate = pixels[0].date;
    let lastDate = undefined;
    let clr = undefined;

    let entities = dataSource.entities;
    entities.suspendEvents();
    //uiCesium.removeDataSource(dataSource);
    entities.values.forEach(e => e.show = false);

    for (var i = pixels.length - 1; i >= 0; i--) {
        let pix = pixels[i];

        if (pix.date != lastDate) {
            clr = getPixelColor(pix, refDate);
            lastDate = pix.date;
        }

        if (clr) {
            setPixelAsset(entities, pix, clr);
            if (clr === timeSteps[0].color && pix.brightness > brightThreshold.kelvin) {
                setBrightAsset(entities, pix);
            }
        }
    }

    //uiCesium.addDataSource(dataSource);
    entities.resumeEvents();
    uiCesium.requestRender();
}

const pixGranularity = Cesium.Math.toRadians(10);

function setPixelAsset(entities, pix, clr) {
    let e = entities.getById(pix.id);
    if (e) {
        e.ellipse.material = clr;
        e.show = true;

    } else {
        let r = pix.size / 2;
        let e = new Cesium.Entity({
            id: pix.id,
            position: Cesium.Cartesian3.fromDegrees(pix.lon, pix.lat),
            ellipse: {
                //center: pos,
                material: clr,
                semiMajorAxis: r,
                semiMinorAxis: r,
                granularity: pixGranularity,
                vertexFormat: Cesium.VertexFormat.POSITION_ONLY
            }
        });
        entities.add(e);
    }
}

const dd = 0.0015;

function _setPixelAsset(entities, pix, clr) {
    let e = entities.getById(pix.id);
    if (e) {
        e.rectangle.material = clr;
        e.show = true;
    } else {
        let e = new Cesium.Entity({
            id: pix.id,
            position: Cesium.Cartesian3.fromDegrees(pix.lon, pix.lat, 0.0),
            rectangle: {
                coordinates: Cesium.Rectangle.fromDegrees(pix.lon - dd, pix.lat - dd, pix.lon + dd, pix.lat + dd),
                material: clr
            }
        });
        entities.add(e);
    }
}

function setBrightAsset(entities, pix) {
    let bid = pix.id + "-bright";
    let e = entities.getById(bid);
    if (e) {
        e.show = true;

    } else {
        e = new Cesium.Entity({
            id: bid,
            position: Cesium.Cartesian3.fromDegrees(pix.lon, pix.lat, 1.0),
            point: {
                color: brightThreshold.color,
                heightReference: Cesium.HeightReference.RELATIVE_TO_GROUND,
                pixelSize: 3
            }
        });
        entities.add(e);
    }
}

function getPixelColor(pixel, refDate) {
    let dt = (refDate - pixel.date) / 3600000; // in hours
    for (var i = 0; i < timeSteps.length; i++) {
        let ts = timeSteps[i];
        if (dt < ts.hours) return ts.color;
    }
    return timeSteps[timeSteps.length - 1].color; // TODO - shall we use the last as the catch-all?
}

//--- interaction

ui.exportToMain(function toggleShowHotspots(event) {
    uiCesium.toggleDataSource(dataSource);
    uiCesium.requestRender();
});

ui.exportToMain(function setHotspotHistory(event) {

});

ui.exportToMain(function selectHotspotTime(event) {
    let selIdx = ui.getSelectedListItemIndex(hotspotView);
    if (selIdx >= 0) {
        let pixels = computeHotspotPixels(selIdx);
        //if (pixels.length > 100) uiCesium.lowerFrameRateFor(pixels.length * 5, 5);
        createPixelAssets(pixels);
    }
});

ui.exportToMain(function earliestHotspotTime(event) {
    ui.selectLastListItem(hotspotView);
});

ui.exportToMain(function earlierHotspotTime(event) {
    ui.selectNextListItem(hotspotView);
});

ui.exportToMain(function laterHotspotTime(event) {
    ui.selectPrevListItem(hotspotView);
});

ui.exportToMain(function latestHotspotTime(event) {
    ui.selectFirstListItem(hotspotView);
});

ui.exportToMain(function clearHotspots() {
    ui.clearSelectedListItem(hotspotView);
    dataSource.entities.removeAll();
});

ui.exportToMain(function resetDisplayParams() {
    timeSteps = structuredClone(config.hotspot.timeSteps);
    brightThreshold = structuredClone(config.hotspot.bright);
    frpThreshold = structuredClone(config.hotspot.frp);
});
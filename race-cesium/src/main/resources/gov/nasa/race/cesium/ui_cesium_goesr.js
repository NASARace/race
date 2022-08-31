import * as config from "./config.js";
import * as ws from "./ws.js";
import * as util from "./ui_util.js";
import * as ui from "./ui.js";
import * as uiCesium from "./ui_cesium.js";

class SatelliteEntry {
    constructor(sat) {
        this.show = sat.show; // can be changed interactively
        this.hotspotEntries = undefined; // updated through goesrHotspots messages
        this.sat = sat;

        this.nTotal = 0;
        this.nGood = 0;
        this.nProbable = 0;
        this.date = 0;
    }

    setStats(hs) {
        this.nTotal = hs.nTotal;
        this.nGood = hs.nGood;
        this.nProbable = hs.nProbable;
        this.date = hs.date;
    }
}

class HotspotEntry {
    constructor(satId, satName, lastSatDate, h) {
        this.hotspot = h;

        this.id = `${h.lat},${h.lon}`;
        this.satId = satId;
        this.satName = satName;
        this.lastSatDate = lastSatDate;
        this.date = h.history[0].date;
        this.mask = h.history[0].mask;
        this.missingMin = Math.round((this.lastSatDate - this.date) / 60000);
        this.nGood = util.countMatching(h.history, r => r.mask == 10 || r.mask == 30);
        this.nTotal = h.history.length;

        this.asset = undefined;
        this.createAssets();
    }

    isGoodPixel() {
        return (this.mask == 10 || this.mask == 30);
    }

    isSaturatedPixel() {
        return (this.mask == 11 || this.mask == 31);
    }

    isCloudPixel() {
        return (this.mask == 12 || this.mask == 32);
    }

    isProbablePixel() {
        return (this.mask == 13 || this.mask == 14 || this.mask == 33 || this.mask == 34);
    }

    hadAnyGoodPixel() {
        return this.hotspot.history.find(h => h.mask == 10 || h.mask == 30);
    }

    classifier() {
        if (this.missingMin == 0) {
            if (this.isGoodPixel()) return ui.createImage("goesr-asset/fire");
            else if (this.isProbablePixel()) return " ⚠︎";
            else return "";
        } else {
            return `-${this.missingMin}m`;
        }
    }

    polygon() {
        let vertices = this.hotspot.history[0].bounds.map(p => new Cesium.Cartesian3.fromDegrees(p[1], p[0]));
        return new Cesium.PolygonHierarchy(vertices);
    }

    polygonMaterial() { // those should be translucent
        if (this.isGoodPixel()) return config.goesr.goodFillColor;
        else if (this.isProbablePixel()) return config.goesr.probableFillColor;
        else return config.goesr.otherFillColor;
    }

    color() {
        if (this.isGoodPixel() || ((this.isCloudPixel() || this.isSaturatedPixel()) && this.hadAnyGoodPixel())) return config.goesr.goodColor;
        else if (this.isProbablePixel()) return config.goesr.probableColor;
        else if (this.isSaturatedPixel()) return config.goesr.saturatedColor;
        else if (this.isCloudPixel()) return config.goesr.cloudColor;
        else return config.goesr.otherColor;
    }

    outlineColor() {
        if (this.missingMin) {
            return Cesium.Color.BLUE;
            //return config.goesr.missingColor;
        } else {
            if (this.isGoodPixel()) return config.goesr.goodOutlineColor;
            else if (this.isCloudPixel()) return config.goesr.cloudColor;
            else if (this.isSaturatedPixel()) return config.goesr.saturatedColor;
            else if (this.isProbablePixel()) return config.goesr.probableOutlineColor;
            else return config.goesr.otherColor;
        }
    }

    outlineWidth() {
        if (this.isGoodPixel()) return config.goesr.strongOutlineWidth; // make this more prominent
        else return config.goesr.outlineWidth;
    }


    createAssets() {
        let lat = this.hotspot.history[0].lat;
        let lon = this.hotspot.history[0].lon;
        let pos = Cesium.Cartesian3.fromDegrees(lon, lat);
        let clr = this.color();

        this.asset = new Cesium.Entity({
            id: this.id,
            position: pos,
            point: {
                pixelSize: config.goesr.pointSize,
                color: clr,
                outlineColor: this.outlineColor(),
                outlineWidth: this.outlineWidth(),
                distanceDisplayCondition: config.goesr.pointDC,
                disableDepthTestDistance: Number.NEGATIVE_INFINITY
            },
            polygon: {
                hierarchy: this.polygon(),
                fill: true,
                material: this.polygonMaterial(),
                outline: true,
                outlineColor: clr,
                outlineWidth: this.outlineWidth(),
                distanceDisplayCondition: config.goesr.boundsDC,
                height: 0
                    //zIndex: 1
            },
            _uiGoesrEntry: this // backlink for selection
        });
    }

    addAssets(dataSource) {
        dataSource.entities.add(this.asset);
    }

    show(cond) {
        this.asset.show = cond;
    }
}

const maskDesc = new Map();
maskDesc
    .set( 10, "good_fire_pixel")
    .set( 11, "saturated_fire_pixel")
    .set( 12, "cloud_contaminated_fire_pixel")
    .set( 13, "high_probability_fire_pixel")
    .set( 14, "medium_probability_fire_pixel")
    .set( 15, "low_probability_fire_pixel")
    .set( 30, "temporally_filtered_good_fire_pixel")
    .set( 31, "temporally_filtered_saturated_fire_pixel")
    .set( 32, "temporally_filtered_cloud_contaminated_fire_pixel")
    .set( 33, "temporally_filtered_high_probability_fire_pixel")
    .set( 34, "temporally_filtered_medium_probability_fire_pixel")
    .set( 35, "temporally_filtered_low_probability_fire_pixel");

var satelliteEntries = [];
var satelliteView = undefined;

var hotspots = [];
var hotspotView = undefined;
var selectedHotspot = undefined;

var historyView = undefined; // for selected hotspot
var maskLabel = undefined;

var pixelLevel = undefined;
var latestOnly = false; // do we just show pixels reported in the last batch

var goesrDataSource = new Cesium.CustomDataSource("goesr");

ui.registerLoadFunction(function initialize() {
    uiCesium.addDataSource(goesrDataSource);

    satelliteView = initSatelliteView();
    hotspotView = initHotspotView();
    historyView = initHistoryView();
    maskLabel = ui.getLabel("goesr.mask");

    setPixelLevel(config.goesr.pixelLevel);
    setLatestOnly(config.goesr.latestOnly);

    uiCesium.setEntitySelectionHandler(goesrSelection);
    ws.addWsHandler(config.wsUrl, handleWsGoesrMessages);

    uiCesium.initLayerPanel("goesr", config.goesr, showGoesr);
    console.log("ui_cesium_goesr initialized");
});

function initSatelliteView() {
    let view = ui.getList("goesr.satellites");
    if (view) {
        ui.setListItemDisplayColumns(view, ["fit", "header"], [
            { name: "", width: "2rem", attrs: [], map: e => ui.createCheckBox(e.show, toggleShowSatellite) },
            { name: "sat", width: "4rem", attrs: [], map: e => e.sat.name },
            { name: "good", width: "3rem", attrs: ["fixed", "alignRight"], map: e => e.nGood },
            { name: "prob", width: "3rem", attrs: ["fixed", "alignRight"], map: e => e.nProbable },
            { name: "all", width: "3rem", attrs: ["fixed", "alignRight"], map: e => e.nTotal },
            { name: "time", width: "5rem", attrs: ["fixed", "alignRight"], map: e => util.toLocalHMTimeString(e.date) }
        ]);
    }
    return view;
}

function initHotspotView() {
    let view = ui.getList("goesr.hotspots");
    if (view) {
        ui.setListItemDisplayColumns(view, ["fit", "header"], [
            { name: "class", width: "3rem", attrs: ["fixed", "alignRight"], map: e => e.classifier() },
            ui.listItemSpacerColumn(),
            { name: "sat", width: "4rem", attrs: [], map: e => e.satName },
            { name: "good", width: "2rem", attrs: ["fixed", "alignRight"], map: e => e.nGood },
            { name: "all", width: "2rem", attrs: ["fixed", "alignRight"], map: e => e.nTotal },
            { name: "lat", width: "6rem", attrs: ["fixed", "alignRight"], map: e => util.f_4.format(e.hotspot.lat) },
            { name: "lon", width: "7rem", attrs: ["fixed", "alignRight"], map: e => util.f_4.format(e.hotspot.lon) },
        ]);
    }
    return view;
}

function initHistoryView() {
    let view = ui.getList("goesr.history");
    if (view) {
        ui.setListItemDisplayColumns(view, ["fit", "header"], [
            { name: "mask", width: "3rem", attrs: ["fixed", "alignRight"], map: e => e.mask },
            { name: "temp", width: "4rem", attrs: ["fixed", "alignRight"], map: e => isNaN(e.temp) ? "-" : util.f_0.format(e.temp) },
            { name: "frp", width: "4rem", attrs: ["fixed", "alignRight"], map: e => isNaN(e.frp) ? "-" : util.f_0.format(e.frp) },
            { name: "area", width: "4rem", attrs: ["fixed", "alignRight"], map: e => isNaN(e.area) ? "-" : util.f_0.format(util.squareMetersToAcres(e.area)) },
            { name: "time", width: "5rem", attrs: ["fixed", "alignRight"], map: e => util.toLocalHMTimeString(e.date) },
        ]);
    }
    return view;
}

function goesrSelection() {
    let sel = uiCesium.getSelectedEntity();
    if (sel && sel._uiGoesrEntry) {
        let he = sel._uiGoesrEntry;
        if (selectedHotspot != he) {
            ui.setSelectedListItem(hotspotView, he);
        }
    }
}

function setPixelLevel(newLevel) {
    let eid = "goesr." + newLevel;
    let e = ui.getRadio(eid);
    if (e) {
        ui.selectRadio(e);
        pixelLevel = newLevel;
        updateHotspotList();
    }
}

function setLatestOnly(cond) {
    latestOnly = cond;

    let eid = "goesr.latest";
    let e = ui.getCheckBox(eid);
    if (e) {
        ui.setCheckBox(e, latestOnly);
    }

    updateHotspotList();
}

function handleWsGoesrMessages(msgType, msg) {
    switch (msgType) {
        case "goesrSatellites":
            handleGoesrSatellites(msg.goesrSatellites);
            return true;
        case "goesrHotspots":
            handleGoesrHotspots(msg.goesrHotspots);
            return true;
        default:
            return false;
    }
}

function handleGoesrSatellites(goesrSatellites) {
    satelliteEntries = goesrSatellites.map(s => new SatelliteEntry(s));
    ui.setListItems(satelliteView, satelliteEntries);
}

function handleGoesrHotspots(goesrHotspots) {
    goesrHotspots.forEach(hs => {
        let e = satelliteEntries.find(se => hs.satId === se.sat.satId);
        if (e) { // a satellite we know about
            e.setStats(hs);
            e.hotspotEntries = hs.hotspots.map(h => new HotspotEntry(hs.satId, e.sat.name, hs.date, h));
            ui.updateListItem(satelliteView, e);
        }
    });
    updateHotspotList();
}

function filterPixelLevel(he) {
    return (pixelLevel == "all" ||
        (pixelLevel == "good" && he.isGoodPixel()) ||
        (pixelLevel == "probable" && he.isProbablePixel()));
}

function updateHotspotList() {
    // TODO - we should make an attempt to keep selections

    let lastSel = selectedHotspot;
    let a = [];
    satelliteEntries.forEach(se => {
        if (se.show && se.hotspotEntries) {
            se.hotspotEntries.forEach(he => {
                if (!latestOnly || he.date == se.date) {
                    if (filterPixelLevel(he)) {
                        a.push(he);
                    }
                }
            });
        }
    });
    a.sort(sortHotspots);

    hotspots = a;
    selectedHotspot = null;

    ui.setListItems(hotspotView, hotspots);
    ui.setListItems(historyView, null);
    ui.setLabelText(maskLabel, null);

    goesrDataSource.entities.removeAll();
    hotspots.forEach(he => {
        he.addAssets(goesrDataSource);
        if (lastSel && lastSel.id == he.id) {
            selectedHotspot = he;
            ui.setSelectedListItem(hotspotView, he);
        }
    });

    uiCesium.requestRender();
}

// approximation of a cluster function 
function sortHotspots(a, b) {
    let x = a.hotspot.lat + a.hotspot.lon;
    let y = b.hotspot.lat + b.hotspot.lon;
    return (x < y) ? -1 : 1;
}

function toggleShowSatellite(event) {
    let cb = ui.getCheckBox(event.target);
    if (cb) {
        let se = ui.getListItemOfElement(cb);
        if (se) {
            se.show = ui.isCheckBoxSelected(cb);
            updateHotspotList();
        }
    }
}

function showGoesr(cond) {
    hotspots.forEach(he => he.asset.show = cond);
}

ui.exportToMain(function selectGoesrSatellite(event) {});

ui.exportToMain(function setGoesrGoodPixels(event) {
    pixelLevel = "good";
    updateHotspotList();
});

ui.exportToMain(function setGoesrProbablePixels(event) {
    pixelLevel = "probable";
    updateHotspotList();
});

ui.exportToMain(function setGoesrAllPixels(event) {
    pixelLevel = "all";
    updateHotspotList();
});

ui.exportToMain(function toggleGoesrLatestPixelsOnly(event) {
    let cb = ui.getCheckBox(event.target);
    if (cb) {
        latestOnly = ui.isCheckBoxSelected(cb);
        updateHotspotList();
    }
});

ui.exportToMain(function selectGoesrHotspot(event) {
    let he = event.detail.curSelection;
    if (he) {
        selectedHotspot = he;
        uiCesium.setSelectedEntity(he.asset);
        ui.setListItems(historyView, he.hotspot.history);
        ui.setLabelText(maskLabel, null);
    }
    uiCesium.requestRender();
});

ui.exportToMain(function selectGoesrHistory(event) {
    if (selectedHotspot) {
        let h = event.detail.curSelection;
        ui.setLabelText(maskLabel, h ? getMaskDescription(h.mask) : null);
    }
});

function getMaskDescription(mask) {
    let desc = maskDesc.get(mask);
    return desc ? desc : "";
}

ui.exportToMain(function zoomToGoesrHotspot(event) {
    let lv = ui.getList(event);
    if (lv) {
        let he = ui.getSelectedListItem(lv);
        if (he) {
            let h = he.hotspot.history[0];
            uiCesium.zoomTo(Cesium.Cartesian3.fromDegrees(h.lon, h.lat, config.goesr.zoomHeight));
        }
    }
});
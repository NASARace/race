import * as config from "./config.js";
import * as ws from "./ws.js";
import { SkipList, CircularBuffer } from "./ui_data.js";
import * as util from "./ui_util.js";
import * as ui from "./ui.js";
import * as uiCesium from "./ui_cesium.js";

const FIRE = "🔥"

var sentinelDataSource = new Cesium.CustomDataSource("sentinel");
var sentinelView = undefined;
var sentinelEntries = new Map();
var sentinelList = new SkipList( // id-sorted display list for trackEntryView
    3, // max depth
    (a, b) => a.id < b.id, // sort function
    (a, b) => a.id == b.id // identity function
);

class SentinelAssets {
    constructor(symbol, details) {
        this.symbol = symbol; // billboard
        this.details = details; // gas coverage, camera-coverage, wind
        this.fire = undefined;
    }
}

class SentinelEntry {
    constructor(sentinel) {
        this.id = sentinel.id;
        this.sentinel = sentinel;
        this.showDetails = false;

        this.assets = null;
    }

    alertStatus() {
        if (this.sentinel.fire) {
            if (this.sentinel.fire.fireProb > 0.5) return FIRE;
        }
        return "";
    }

    setShowDetails(showIt) {
        this.showDetails = showIt;
        if (this.assets && this.assets.details) this.assets.details.show = showIt
    }
}

export function initialize() {
    uiCesium.addDataSource(sentinelDataSource);
    sentinelView = initSentinelView();

    //uiCesium.setEntitySelectionHandler(trackSelection);
    ws.addWsHandler(handleWsSentinelMessages);

    return true;
}

function initSentinelView() {
    let view = ui.getList("sentinel.list");
    if (view) {
        ui.setListItemDisplayColumns(view, ["fit"], [
            { name: "show", width: "2rem", attrs: [], map: e => ui.createCheckBox(e.showDetails, toggleShowDetails, null) },
            { name: "id", width: "2rem", attrs: ["alignLeft"], map: e => e.id },
            { name: "alert", width: "1.5rem", attrs: [], map: e => e.alertStatus() },
            { name: "prob", width: "2rem", attrs: ["fixed"], map: e => util.f_1.format(e.sentinel.fire.fireProb) },
            { name: "date", width: "5rem", attrs: ["fixed", "alignRight"], map: e => util.toLocalTimeString(e.sentinel.date) }
        ]);
    }
    return view;
}

function toggleShowDetails(event) {
    let cb = ui.getCheckBox(event.target);
    if (cb) {
        let e = ui.getListItemOfElement(cb);
        if (e) {
            e.setShowDetails(ui.isCheckBoxSelected(cb));
        }
    }
}

function handleWsSentinelMessages(msgType, msg) {
    switch (msgType) {
        case "sentinel":
            handleSentinelMessage(msg.sentinel);
            return true;
        default:
            return false;
    }
}

function handleSentinelMessage(sentinel) {
    //console.log(JSON.stringify(sentinel));

    let e = sentinelEntries.get(sentinel.id)
    if (e) {
        updateSentinelEntry(e, sentinel);
    } else {
        addSentinelEntry(sentinel);
    }
}

function addSentinelEntry(sentinel) {
    let e = new SentinelEntry(sentinel);

    sentinelEntries.set(sentinel.id, e);
    let idx = sentinelList.insert(e);
    ui.insertListItem(sentinelView, e, idx);

    if (sentinel.gps) e.assets = createAssets(sentinel);
    checkFireAsset(e);
}

function updateSentinelEntry(e, sentinel) {
    let old = e.sentinel;

    //console.log(JSON.stringify(sentinel));
    e.sentinel = sentinel;
    ui.updateListItem(sentinelView, e);

    if (!old.gps && sentinel.gps) {
        e.assets = createAssets(sentinel);
    }
    checkFireAsset(e);
}

function checkFireAsset(e) {
    let sentinel = e.sentinel;

    if (sentinel.gps && e.alertStatus() === FIRE) { // TODO
        if (e.assets) {
            if (!e.assets.fire) {
                e.assets.fire = createFireAsset(sentinel);
                if (e.assets.fire) e.assets.fire.show = true;
            } else {
                // update fire location/probability
            }
        }
    }
}

function createAssets(sentinel) {
    return new SentinelAssets(
        createSymbolAsset(sentinel),
        createDetailAsset(sentinel)
    );
}

function createSymbolAsset(sentinel) {
    let entity = new Cesium.Entity({
        id: sentinel.id,
        position: Cesium.Cartesian3.fromDegrees(sentinel.gps.longitude, sentinel.gps.latitude),
        billboard: {
            image: 'sentinel-asset/sentinel',
            distanceDisplayCondition: config.sentinelBillboardDC,
            color: config.sentinelColor,
            heightReference: Cesium.HeightReference.CLAMP_TO_GROUND,
        },
        label: {
            text: sentinel.id.toString(),
            scale: 0.8,
            horizontalOrigin: Cesium.HorizontalOrigin.LEFT,
            verticalOrigin: Cesium.VerticalOrigin.TOP,
            font: config.trackLabelFont,
            fillColor: config.sentinelColor,
            showBackground: true,
            backgroundColor: config.trackLabelBackground,
            pixelOffset: config.sentinelLabelOffset,
            distanceDisplayCondition: config.sentinelBillboardDC,
        }
    });
    sentinelDataSource.entities.add(entity);
    return entity;
}

// TODO - this is a mockup
function createDetailAsset(sentinel) {
    let entity = new Cesium.Entity({
        id: sentinel.id + "-details",
        distanceDisplayCondition: config.sentinelBillboardDC,
        position: Cesium.Cartesian3.fromDegrees(sentinel.gps.longitude, sentinel.gps.latitude),
        ellipse: {
            semiMinorAxis: 500,
            semiMajorAxis: 1000,
            rotation: Cesium.Math.toRadians(45),
            material: Cesium.Color.ALICEBLUE.withAlpha(0.3),
        },
        show: false // only when selected
    });
    sentinelDataSource.entities.add(entity);
    return entity;
}

// TODO - this is a mockup
function createFireAsset(sentinel) {
    let entity = new Cesium.Entity({
        id: sentinel.id + "-fire",
        distanceDisplayCondition: config.sentinelBillboardDC,
        position: Cesium.Cartesian3.fromDegrees(sentinel.gps.longitude - 0.002, sentinel.gps.latitude - 0.002, 0),
        ellipse: {
            semiMinorAxis: 25,
            semiMajorAxis: 50,
            rotation: Cesium.Math.toRadians(-45),
            material: Cesium.Color.RED.withAlpha(0.7),
            outline: true,
            outlineColor: Cesium.Color.RED

        }
    });
    sentinelDataSource.entities.add(entity);
    return entity;
}

ui.exportToMain(function selectSentinel(event) {
    let e = event.detail.curSelection;
    if (e) {
        // if (e.assets && e.assets.details) e.assets.details.show = true;
    }
});
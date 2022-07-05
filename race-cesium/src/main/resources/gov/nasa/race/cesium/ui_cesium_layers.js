// cesium KML layer module
import * as config from "./config.js";
import * as ws from "./ws.js";
import * as util from "./ui_util.js";
import { SkipList } from "./ui_data.js";
import * as ui from "./ui.js";
import * as uiCesium from "./ui_cesium.js";

var layerView = undefined;
var layerEntries = new Map();
var layerEntryList = new SkipList( // id-sorted display list for trackEntryView
    3, // max depth
    (a, b) => a.id < b.id, // sort function
    (a, b) => a.id === b.id // identity function
);

const REMOTE = "";
const LOADING = "…";
const LOADED = "○";
const SHOWING = "●";

class LayerEntry {
    constructor(layer) {
        this.id = layer.name;
        this.show = layer.show; // layer.show is only the initial value
        this.layer = layer;

        this.status = REMOTE;
        this.dataSource = undefined; // set upon XMLHttpRequest completion
    }

    setVisible(isVisible) {
        if (isVisible != this.show) {
            this.show = isVisible;
            if (this.dataSource) this.dataSource.show = isVisible;

            if (isVisible) {
                if (this.status == REMOTE) {
                    this.status = LOADING;
                    loadLayer(this);
                } else if (this.status == LOADED) {
                    this.status = SHOWING;
                }

            } else {
                if (this.status == SHOWING) {
                    this.status = LOADED;
                }
            }
        }
    }
}


ui.registerLoadFunction(function initialize() {
    layerView = initLayerView();

    ws.addWsHandler(config.wsUrl, handleWsLayerMessages);
    console.log("ui_cesium_layers initialized");
});

function initLayerView() {
    let view = ui.getList("layers.list");
    if (view) {
        ui.setListItemDisplayColumns(view, ["fit"], [
            { name: "show", width: "2rem", attrs: [], map: e => ui.createCheckBox(e.show, toggleShowLayer) },
            { name: "status", width: "2rem", attrs: [], map: e => e.status },
            { name: "id", width: "10rem", attrs: ["alignLeft"], map: e => e.id },
            {
                name: "date",
                width: "6rem",
                attrs: ["fixed", "alignRight"],
                map: e => util.toLocalTimeString(e.layer.date)
            }
        ]);
    }
    return view;
}

function toggleShowLayer(event) {
    let cb = ui.getCheckBox(event.target);
    if (cb) {
        let le = ui.getListItemOfElement(cb);
        if (le) {
            le.setVisible(ui.isCheckBoxSelected(cb));
            ui.updateListItem(layerView, le);
            uiCesium.requestRender();
        }
    }
}

function handleWsLayerMessages(msgType, msg) {
    switch (msgType) {
        case "layer":
            handleLayerMessage(msg.layer);
            return true;
        default:
            return false;
    }
}

function handleLayerMessage(layer) {
    let e = layerEntries.get(layer.name);
    if (e) {
        e.layer = layer;
        ui.updateListItem(layerView, e);
    } else {
        e = new LayerEntry(layer);
        layerEntries.set(layer.name, e);
        let idx = layerEntryList.insert(e);
        ui.insertListItem(layerView, e, idx);
    }

    if (layer.show) {
        setTimeout(() => loadLayer(e), 3000); // FIXME - only defer if page is loading
    }
}

function loadLayer(layerEntry) {
    let resource = new Cesium.Resource({
        url: layerEntry.layer.url,
        proxy: new Cesium.DefaultProxy('proxy') // proxy through RACE since KML is notorious for including non-CORS links
    });

    var fut = _loadDataSource(layerEntry, resource);
    if (fut) {
        fut.then(ds => {
            console.log("layer loaded " + ds.name);
            //ds.show = layerEntry.show;
            layerEntry.dataSource = ds;
            layerEntry.status = layerEntry.show ? SHOWING : LOADED;
            ui.updateListItem(layerView, layerEntry);

            uiCesium.viewer.dataSources.add(ds);
        });
    }
}

function _loadDataSource(layerEntry, resource) {
    let viewer = uiCesium.viewer;
    let url = layerEntry.layer.url;

    if (url.includes('.kml') || url.includes('.kmz')) {
        return Cesium.KmlDataSource.load(resource, {
            camera: viewer.scene.camera,
            canvas: viewer.scene.canvas,
            screenOverlayContainer: viewer.container,
        });

    } else if (url.includes('.geojson') || url.includes('.topojson')) { // TODO - need to get render params from server
        return new Cesium.GeoJsonDataSource.load(resource, {
            stroke: Cesium.Color.HOTPINK,
            fill: Cesium.Color.PINK,
            strokeWidth: 1,
            markerSymbol: '?'
        });
    }

    return undefined; // don't know layer type
}

//--- user interface
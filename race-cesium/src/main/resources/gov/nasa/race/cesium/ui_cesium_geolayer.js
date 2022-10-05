import * as config from "./config.js";
import * as ws from "./ws.js";
import * as util from "./ui_util.js";
import * as ui from "./ui.js";
import * as uiCesium from "./ui_cesium.js";

class SourceEntry {
    constructor(source) {
        this.source = source;
        this.show = false;
        this.dataSource = undefined;
    }
}

var sources = []; // will be populated by getLayers messages
var sourceView = undefined;
var defaultRender = config.geolayer.render;

ui.registerLoadFunction(function initialize() {
    sourceView = initSourceView();

    uiCesium.setEntitySelectionHandler(geoLayerSelection);
    ws.addWsHandler(config.wsUrl, handleWsGeoLayerMessages);

    uiCesium.initLayerPanel("geolayer", config.geolayer, showGeoLayer);
    console.log("ui_cesium_geolayer initialized");
});

function geoLayerSelection() {
    let sel = uiCesium.getSelectedEntity();
    if (sel) {
        // sel.properties.propertyNames.forEach( n=> {
        //     let v = sel.properties[n]._value;
        //     console.log( "@@ " + n + " = " + v);
        // })
    }
}

function initSourceView() {
    let view = ui.getList("geolayer.source.list");
    if (view) {
        ui.setListItemDisplayColumns(view, ["fit"], [
            { name: "", width: "2rem", attrs: [], map: e => ui.createCheckBox(e.show, toggleShowSource) },
            { name: "path", width: "12rem", attrs: [], map: e=> e.source.pathName }
        ]);
    }
    return view;
}

function toggleShowSource(event) {
    let cb = ui.getCheckBox(event.target);
    if (cb) {
        let se = ui.getListItemOfElement(cb);
        if (se) {
            se.show = ui.isCheckBoxSelected(cb);
            if (se.show) {
                loadSource(se);
            } else {
                unloadSource(se);
            }
        }
    }
}

function handleWsGeoLayerMessages(msgType, msg) {
    switch (msgType) {
        case "geoLayers": handleGeoLayersMessage(msg.geoLayers); break;
    }
}

function handleGeoLayersMessage(geoLayers) {
    // TODO - needs to handle updates differently from init
    sources = geoLayers.map( src=> new SourceEntry(src));
    ui.setListItems( sourceView, sources);
}

function loadSource(sourceEntry) {
    let url = "geolayer-data/" + sourceEntry.source.pathName;

    return new Promise(function(resolve) {
        var request = new XMLHttpRequest();
        request.open('GET', url);
        request.responseType = "json";

        request.onload = function() {
            let data = request.response;
            if (data) {
                let renderOpts = collectRenderOpts(sourceEntry);
                new Cesium.GeoJsonDataSource.load(data, renderOpts).then(
                    ds => {
                        sourceEntry.dataSource = ds;
                        uiCesium.viewer.dataSources.add(ds);
                        uiCesium.requestRender();
                        setTimeout( () => uiCesium.requestRender(), 300); // ??
                    } 
                );
            }
        }
        request.send();
    });
}

function collectRenderOpts (sourceEntry) {
    let o = {
        ...defaultRender,
        ...sourceEntry.source.render
    };

    // this should go into initialization
    if (o.stroke && util.isString(o.stroke)) o.stroke = Cesium.Color.fromCssColorString(o.stroke);
    if (o.markerColor && util.isString(o.markerColor)) o.markerColor = Cesium.Color.fromCssColorString(o.markerColor);
    if (o.fill && util.isString(o.fill)) o.fill = Cesium.Color.fromCssColorString(o.fill);

    return o;
}

function setEntityProperties (ds, renderOpts) {
    // set points, billboard symbols, set DCs copy GeoJSON features etc
    let entities = ds.entities.values;
    for (var i = 0; i < entities.length; i++) {
        var entity = entities[i];
        //entity.billboard = undefined;
    }
}

function unloadSource(sourceEntry) {
    if (sourceEntry.dataSource) {
        sourceEntry.dataSource.show = false;
        uiCesium.viewer.dataSources.remove(sourceEntry.dataSource, true);
        sourceEntry.dataSource = undefined;
        uiCesium.requestRender();
    }
}

function showGeoLayer(cond) {
}

ui.exportToMain(function selectGeoLayerSource(event) {
    let e = event.detail.curSelection;
    if (e) {
        console.log("selected: " + e);
    }
});
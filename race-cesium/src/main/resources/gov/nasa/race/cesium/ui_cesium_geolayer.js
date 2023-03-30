import * as config from "./config.js";
import * as ws from "./ws.js";
import * as util from "./ui_util.js";
import { ExpandableTreeNode } from "./ui_data.js";
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

var objectView = undefined;

const renderModules = new Map();
var defaultRenderFunc = undefined;

ui.registerLoadFunction(function initialize() {
    sourceView = initSourceView();
    objectView = ui.getKvTable("geolayer.object");

    uiCesium.setEntitySelectionHandler(geoLayerSelection);
    ws.addWsHandler(config.wsUrl, handleWsGeoLayerMessages);

    if (config.geolayer.render) processRenderOpts(config.geolayer.render);

    uiCesium.initLayerPanel("geolayer", config.geolayer, showGeoLayer);
    console.log("ui_cesium_geolayer initialized");
});

async function loadRenderModule (modPath,sourceEntry=null) {
    let renderFunc = renderModules.get(modPath);
    if (!renderFunc) {
        try {
            const { render } = await import(modPath);
            if (render) {
                renderModules.set(modPath, render);
                renderFunc = render;
            }
        } catch (error) {
            console.log(error);
        }
    }

    if (renderFunc) { 
        if (sourceEntry) sourceEntry.renderFunc = renderFunc;
        else defaultRenderFunc = renderFunc;
    }
}

function geoLayerSelection() {
    let e = uiCesium.getSelectedEntity();
    if (e && e.properties && e.properties.propertyNames) {
        let kvList = e.properties.propertyNames.map( key=> [key, e.properties[key]._value]);
        ui.setKvList(objectView,kvList);
    } else {
        ui.setKvList(objectView,null);
    }
}

function initSourceView() {
    let view = ui.getList("geolayer.source.list");
    if (view) {
        ui.setListItemDisplayColumns(view, ["header"], [
            { name: "date", width: "8rem", attrs: ["fixed", "alignRight"], map: e => util.toLocalDateString(e.source.date)},
            { name: "objs", tip: "number of loaded objects", width: "5rem", attrs: ["fixed", "alignRight"], map: e => e.nEntities ? e.nEntities : ""},
            ui.listItemSpacerColumn(),
            { name: "show", tip: "toggle visibility", width: "2.1rem", attrs: [], map: e => ui.createCheckBox(e.show, toggleShowSource) }
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
    
    let srcTree = ExpandableTreeNode.fromPreOrdered( sources, e=> e.source.pathName);
    ui.setTree( sourceView, srcTree);

    sources.forEach( e=> {
        if (e.source.render) {
            processRenderOpts( e.source.render, e);
        }
    });
}

function processRenderOpts (opts, sourceEntry=null) {
    if (opts.module) loadRenderModule( opts.module, sourceEntry);

    // transform once into Cesium representation so that we don't re-create similar objects when loading the layer

    if (opts.pointDistance) {
        opts.pointDC = new Cesium.DistanceDisplayCondition(opts.pointDistance, Number.MAX_VALUE);
        opts.billboardDC = new Cesium.DistanceDisplayCondition( 0, opts.pointDistance);
    }

    if (opts.geometryDistance) {
        opts.geometryDC = new Cesium.DistanceDisplayCondition( 0, opts.geometryDistance);
    }

    if (util.isString(opts.markerColor)) opts.markerColor = Cesium.Color.fromCssColorString(opts.markerColor);
    if (util.isString(opts.stroke)) opts.stroke = Cesium.Color.fromCssColorString(opts.stroke);
    if (util.isString(opts.fill)) opts.fill = Cesium.Color.fromCssColorString(opts.fill);
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
                new Cesium.GeoJsonDataSource.load(data, renderOpts).then(  // TODO - does that support streams? 
                    ds => {
                        sourceEntry.dataSource = ds;
                        postProcessDataSource(sourceEntry, renderOpts);
                        sourceEntry.nEntities = ds.entities.values.length;
                        ui.updateListItem(sourceView, sourceEntry);

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
    return {
        ...defaultRender,
        ...sourceEntry.source.render
    };
}

function postProcessDataSource (sourceEntry, renderOpts) {
    let renderFunc = util.firstDefined(sourceEntry.renderFunc, defaultRenderFunc);
    if (renderFunc) {
        renderFunc( sourceEntry.dataSource.entities, renderOpts);
    }
}

function unloadSource(sourceEntry) {
    if (sourceEntry.dataSource) {
        sourceEntry.dataSource.show = false;
        uiCesium.viewer.dataSources.remove(sourceEntry.dataSource, true);
        sourceEntry.dataSource = undefined;
        uiCesium.requestRender();

        sourceEntry.nEntities = undefined;
        ui.updateListItem(sourceView, sourceEntry);
    }
}

function showGeoLayer(cond) {
    sources.forEach( src=> {
        if (src.dataSource) src.dataSource.show = cond;
    });
    uiCesium.requestRender();
}

ui.exportToMain(function selectGeoLayerSource(event) {
    let e = event.detail.curSelection;
    if (e) {
        console.log("selected: ", e);
    }
});
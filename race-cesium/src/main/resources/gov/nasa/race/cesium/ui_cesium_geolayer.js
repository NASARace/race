/*
 * Copyright (c) 2023, United States Government, as represented by the
 * Administrator of the National Aeronautics and Space Administration.
 * All rights reserved.
 *
 * The RACE - Runtime for Airspace Concept Evaluation platform is licensed
 * under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
var objectView = undefined;
var defaultRender = config.geolayer.render;

const renderModules = new Map();
var defaultRenderFunc = undefined;

initWindow();

uiCesium.setEntitySelectionHandler(geoLayerSelection);
ws.addWsHandler(handleWsGeoLayerMessages);

if (config.geolayer.render) processRenderOpts(config.geolayer.render);

uiCesium.initLayerPanel("geolayer", config.geolayer, showGeoLayer);
console.log("ui_cesium_geolayer initialized");

//--- end module init

function initWindow() {
    createIcon();
    createWindow();

    sourceView = initSourceView();
    objectView = ui.getKvTable("geolayer.object");
}

function createWindow() {
    return ui.Window("Geo Layers", "geolayer", "geomarker-icon.svg")(
        ui.LayerPanel("geolayer", toggleShowGeoLayer),
        ui.Panel("geo layer sources", true)(
            ui.TreeList("geolayer.source.list", 15, 25, selectGeoLayerSource)
        ),
        ui.Panel("object data", false)(
            ui.KvTable("geolayer.object", 15, 25,25)
        ),
        ui.Panel("display parameters", false)()
    );
}

function createIcon() {
    return ui.Icon("geomarker-icon.svg", (e)=> ui.toggleWindow(e,'geolayer'));
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

    if (e.position && e.position._value) {
        uiCesium.viewer.selectionIndicator.viewModel.position = e.position._value;  // HACK - cesium has wrong SI height if not clamp-to-ground
    }

    if (e && e.properties && e.properties.propertyNames) {
        let kvList = e.properties.propertyNames.map( key=> [key, e.properties[key]._value]);
        ui.setKvList(objectView,kvList);
    } else {
        ui.setKvList(objectView,null);
    }
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

async function loadSource(sourceEntry) {
    let url = "geolayer-data/" + sourceEntry.source.pathName;

    fetch(url).then( (response) => {
        if (response.ok) {
            let data = response.json();
            if (data) {
                let renderOpts = collectRenderOpts(sourceEntry);
                Cesium.GeoJsonDataSource.load(data, renderOpts).then( (ds) => {
                    ds.show = true;
                    sourceEntry.dataSource = ds;
                    postProcessDataSource(sourceEntry, renderOpts);
                    sourceEntry.nEntities = ds.entities.values.length;
                    ui.updateListItem(sourceView, sourceEntry);
            
                    uiCesium.addDataSource(ds);

                    console.log("loaded ", url);
                    setTimeout( () => { uiCesium.requestRender(); }, 500);  // not showing if immediate request ?
                });
            } else console.log("no data for request: ", url);
        } else console.log("request failed: ", url);
    }, (reason) => console.log("failed to retrieve: ", url, ", reason: ", reason));
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

function selectGeoLayerSource(event) {
    let e = event.detail.curSelection;
    if (e) {
        console.log("selected: ", e);
    }
}

function toggleShowGeoLayer(event) {
    console.log("not yet")
}
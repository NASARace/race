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
import * as util from "./ui_util.js";
import { ExpandableTreeNode } from "./ui_data.js";
import * as ui from "./ui.js";
import * as uiCesium from "./ui_cesium.js";

//--- module initialization

var defaultRender = config.imglayer.render;
var sources = config.imglayer.sources;
var selectedSrc = undefined;

createIcon();
createWindow();

let sourceView = initSourceView();
let cmView = initColorMapView();

initImgLayers();
initImgSliders();

let srcTree = ExpandableTreeNode.fromPreOrdered( sources, e=> e.pathName);
ui.setTree( sourceView, srcTree);

ui.registerThemeChangeHandler(themeChanged);

uiCesium.registerMouseClickHandler(handleMouseClick);
uiCesium.initLayerPanel("imglayer", config.imglayer, showImgLayer);
console.log("ui_cesium_imglayer initialized");

//--- end init

function createIcon() {
    return ui.Icon("globe-icon.svg", (e)=> ui.toggleWindow(e,'imglayer'));
}

function createWindow() {
    return ui.Window("Imagery Layers", "imglayer", "globe-icon.svg")(
        ui.LayerPanel("imglayer", toggleShowImgLayer),
        ui.Panel("sources", true)(
          ui.TreeList("imglayer.source.list", 15, 25, selectImgLayerSrc),
          ui.Text("imglayer.source.info", 25)
        ),
        ui.Panel("color map", false)(
          ui.List("imglayer.cm.list", 15, selectImgCmapEntry),
          ui.Text("imglayer.cm.info", 25)
        ),
        ui.Panel("layer parameters", false)(
          ui.ColumnContainer("align_right")(
            ui.Slider("alpha", "imglayer.render.alpha", setImgAlpha),
            ui.Slider("brightness", "imglayer.render.brightness", setImgBrightness),
            ui.Slider("contrast", "imglayer.render.contrast", setImgContrast),
            ui.Slider("hue", "imglayer.render.hue", setImgHue),
            ui.Slider("saturation", "imglayer.render.saturation", setImgSaturation),
            ui.Slider("gamma", "imglayer.render.gamma", setImgGamma)
          )
        )
    );
}

function initSourceView() {
    let view = ui.getList("imglayer.source.list");
    if (view) {
        ui.setListItemDisplayColumns(view, ["fit", "header"], [
            { name: "exclusive", tip: "only one of each group visible", width: "10rem", attrs: ["alignRight", "small"], map: e=> util.mkString(e.exclusive,',')},
            ui.listItemSpacerColumn(),
            { name: "show", tip: "toggle visibility", width: "2.1rem", attrs: [], map: e => ui.createCheckBox(e.show, toggleShowSource) }
        ]);
    }
    return view;
}

function toggleShowSource(event) {
    let cb = ui.getCheckBox(event.target);
    if (cb) {
        let src = ui.getListItemOfElement(cb);
        if (src) {
            let isSelected = ui.isCheckBoxSelected(cb);
            if (src.show != isSelected) {
                let layer = src.layer;
                if (isSelected) {
                    hideExclusives(src);
                    layer.show = src.show = true;
                } else {
                    layer.show = src.show = false;
                }
            }

            uiCesium.requestRender();
        }
    }
}

function initColorMapView() {
    let view = ui.getList("imglayer.cm.list");
    if (view) {
        ui.setListItemDisplayColumns(view, ["fit", "header"], [
            { name: "color", width: "2rem", attrs: [], map: e=> ui.createColorBox(e.color) },
            { name: "code", width: "3rem", attrs: ["alignRight"], map: e => e.code },
            ui.listItemSpacerColumn(),
            { name: "description", tip: "alt+click on map to look-up", width: "17rem", attrs: [], map: e => e.descr }
        ]);
    }
    return view;
}

function initImgLayers() {
    let viewerLayers = uiCesium.viewer.imageryLayers;
    viewerLayers.removeAll(false);

    for (var i=0; i<sources.length; i++) {
        let src = sources[i];
        if (!src.show) src.show = false;
        let opts = { show: src.show }; // TODO - add other non-render opts here (rectangle, cutoutRectangle, max/minTerrainLevel, ..)
        if (src.render) {
            if (src.render.alphaColor) opts.colorToAlpha = Cesium.Color.fromCssColorString(src.render.alphaColor);
            if (typeof src.render.alphaColorThreshold !== 'undefined') opts.colorToAlphaThreshold = src.render.alphaColorThreshold;
        }
        // note that src.provider can either return a provider object or a promise
        let layer = src.provider ? Cesium.ImageryLayer.fromProviderAsync( Promise.resolve(src.provider), opts) : 
                                   Cesium.ImageryLayer.fromWorldImagery({style: src.style});
        console.log("loaded imagery provider: ", src.pathName, ", show=", src.show);

        src.layer = layer;
        setLayerRendering(layer, {...defaultRender, ...src.render});
        layer.show = src.show;
        viewerLayers.add(layer);

        loadColorMap(src);
    }

    console.log("loaded ", sources.length, " imagery layers");
}



// set rendering parameters in instantiated ImageryLayer
function setLayerRendering (layer,render) {
    layer.alpha = render.alpha;
    layer.brightness = render.brightness;
    layer.contrast = render.contrast;
    layer.hue = render.hue * Math.PI / 180.0;
    layer.saturation = render.saturation;
    layer.gamma = render.gamma;
}

function initImgSliders() {
    let e = ui.getSlider('imglayer.render.alpha');
    ui.setSliderRange(e, 0, 1.0, 0.1, util.f_1);
    ui.setSliderValue(e, defaultRender.alpha);

    e = ui.getSlider('imglayer.render.brightness');
    ui.setSliderRange(e, 0, 2.0, 0.1, util.f_1);
    ui.setSliderValue(e, defaultRender.brightness);

    e = ui.getSlider('imglayer.render.contrast');
    ui.setSliderRange(e, 0, 2.0, 0.1, util.f_1);
    ui.setSliderValue(e, defaultRender.contrast);

    e = ui.getSlider('imglayer.render.hue');
    ui.setSliderRange(e, 0, 360, 1, util.f_0);
    ui.setSliderValue(e, defaultRender.hue);

    e = ui.getSlider('imglayer.render.saturation');
    ui.setSliderRange(e, 0, 2.0, 0.1, util.f_1);
    ui.setSliderValue(e, defaultRender.saturation);

    e = ui.getSlider('imglayer.render.gamma');
    ui.setSliderRange(e, 0, 2.0, 0.1, util.f_1);
    ui.setSliderValue(e, defaultRender.gamma);
}

function setImgSliderValues (src) {
    let e = ui.getSlider('imglayer.render.alpha');
    ui.setSliderValue(e, src.layer.alpha);

    e = ui.getSlider('imglayer.render.brightness');
    ui.setSliderValue(e, src.layer.brightness);

    e = ui.getSlider('imglayer.render.contrast');
    ui.setSliderValue(e, src.layer.contrast);

    e = ui.getSlider('imglayer.render.hue');
    ui.setSliderValue(e, src.layer.hue * 180.0 / Math.PI);

    e = ui.getSlider('imglayer.render.saturation');
    ui.setSliderValue(e, src.layer.saturation);

    e = ui.getSlider('imglayer.render.gamma');
    ui.setSliderValue(e, src.layer.gamma);
}

function hideExclusives (src) {
    let exclusive = src.exclusive;
    if (exclusive && exclusive.length > 0) {
        sources.forEach( s=> {
            if ((s !== src) && util.haveEqualElements(exclusive, s.exclusive)) {
                if (s.show) {
                    s.show = false;
                    s.layer.show = false;
                    ui.updateListItem(sourceView,s);
                }
            }
        });
    }
}

function selectImgLayerSrc(event) {
    let src = ui.getSelectedListItem(sourceView);
    if (src) {
        selectedSrc = src;
        ui.setTextContent("imglayer.source.info", src.info);
        setImgSliderValues(src);
        if (src.cm) ui.setListItems(cmView, src.cm); else ui.clearList(cmView);
    } else {
        ui.clearTextContent("imglayer.source.info");
        ui.clearList(cmView);
    }
}

function selectImgCmapEntry(event) {
    let ce = ui.getSelectedListItem(cmView);
    ui.setTextContent("imglayer.cm.info", ce ? ce.descr : null);
}

var mouseX;
var mouseY;
let pixBuf = new Uint8Array(4);

// watch out - this runs in the render loop so avoid length computation
const probeColor = function(scene,time) {
    let canvas = scene.canvas;
    let gl = canvas.getContext('webgl2');

    gl.readPixels( mouseX, gl.drawingBufferHeight - mouseY, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, pixBuf);

    
    let r = pixBuf[0];
    let g = pixBuf[1];
    let b = pixBuf[2];
    let clr = Cesium.Color.fromBytes(r,g,b);

    //console.log("rgb[" + mouseX + ',' + mouseY + "] = " + r + ',' + g + ',' + b, clr);

    // unfortunately we can't just use exact match and have to find the closest color
    new Promise( function(resolve,reject) {    
        if (selectedSrc && selectedSrc.cm) {
            let cm = selectedSrc.cm;
            let diffClr = new Cesium.Color();
            let minDiff = Number.MAX_VALUE;
            let bestMatch = -1;

            for (var i=0; i<cm.length; i++) {
                Cesium.Color.subtract(cm[i].clr,clr,diffClr);
                Cesium.Color.multiply(diffClr,diffClr,diffClr);
                let diff = diffClr.red + diffClr.green + diffClr.blue;
                if (diff < minDiff) {
                    minDiff = diff;
                    bestMatch = i;
                }
            }

            if (bestMatch >= 0) {
                ui.setSelectedListItemIndex(cmView,bestMatch);
                resolve(bestMatch);
            }
        }
    });

    scene.postRender.removeEventListener(probeColor);
}


function handleMouseClick(event) {
    if (event.altKey) {
        mouseX = event.clientX;
        mouseY = event.clientY;

        uiCesium.viewer.scene.postRender.addEventListener(probeColor);
        uiCesium.requestRender();
    }
}

function showImgLayer (cond) {

}

//--- interactive render parameters

function setImgAlpha(event) {
    if (selectedSrc) {
        let v = ui.getSliderValue(event.target);
        selectedSrc.layer.alpha = v;
        uiCesium.requestRender();
    }
}

function setImgBrightness(event) {
    let v = ui.getSliderValue(event.target);
    setImgLayerBrightness(v);
}

function setImgLayerBrightness(v) {
    if (selectedSrc) {
        selectedSrc.layer.brightness = v;
        uiCesium.requestRender();
    }
}

function setImgContrast(event) {
    if (selectedSrc) {
        let v = ui.getSliderValue(event.target);
        selectedSrc.layer.contrast = v;
        uiCesium.requestRender();
    }
}

function setImgHue(event) {
    let v = ui.getSliderValue(event.target);
    setImgLayerHue(v);
}

function setImgLayerHue(v) {
    if (selectedSrc) {
        selectedSrc.layer.hue = (v * Math.PI) / 180; // needs radians
        uiCesium.requestRender();
    }
}

function setImgSaturation(event) {
    let v = ui.getSliderValue(event.target);
    setImgLayerSaturation(v);
}

function setImgLayerSaturation(v) {
    if (selectedSrc) {
        selectedSrc.layer.saturation = v;
        uiCesium.requestRender();
    }
}

function setImgGamma(event) {
    if (selectedSrc) {
        let v = ui.getSliderValue(event.target);
        selectedSrc.layer.gamma = v;
        uiCesium.requestRender();
    }
}

//--- theme change

function themeChanged() {
    /* TBD
    sources.forEach(li => li.imageryParams = li.originalImageryParams); // restore original imageryParams
    adjustImageryParams(); // adjust defaults according to theme

    if (selectedMapLayer) { // update selectedMapLayer
        updateSelectedMapLayer();
        setMapSliders(selectedMapLayer);
    }
    */ 
}

//--- color maps

function loadColorMap (src) {
    if (src.colorMap) {
        return new Promise(function(resolve) {
            var request = new XMLHttpRequest();
            request.open('GET', src.colorMap);
            //request.responseType = "json";
            request.responseType = "text";

            request.onreadystatechange = function() {
                if (request.readyState === 4) {
                    if (request.response) {
                        let cm = JSON.parse(request.response);
                        src.cm = cm;

                        for (var i=0; i<cm.length; i++) {
                            let ce = cm[i];
                            ce.clr = Cesium.Color.fromCssColorString(ce.color);
                        }
                    }
                }
            }
            request.send();
        });
    }
}

//--- micro service visibility (TODO - does this make sense?)

function toggleShowImgLayer(event) {
    console.log("not yet")
}
import * as config from "./config.js";
import * as ws from "./ws.js";
import * as util from "./ui_util.js";
import { ExpandableTreeNode } from "./ui_data.js";
import * as ui from "./ui.js";
import * as uiCesium from "./ui_cesium.js";

var defaultRender = config.imglayer.render;
var sources = config.imglayer.sources;

var sourceView = undefined;
var selectedSrc = undefined;

var cmView = undefined;

//--- module initialization

ui.registerLoadFunction(function initialize() {
    sourceView = initSourceView();
    cmView = initCmView();

    initImgLayers();
    initImgSliders();

    let srcTree = ExpandableTreeNode.fromPreOrdered( sources, e=> e.pathName);
    ui.setTree( sourceView, srcTree);

    ui.registerThemeChangeHandler(themeChanged);

    uiCesium.registerMouseClickHandler(handleMouseClick);
    uiCesium.initLayerPanel("imglayer", config.imglayer, showImgLayer);
    console.log("ui_cesium_imglayer initialized");
});

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
            if (isSelected) hideExclusives(src);
            src.show = isSelected;

            setViewerLayers();
            uiCesium.requestRender();
        }
    }
}

function initCmView() {
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
    let defaultImageryLayer = viewerLayers.get(0); // the first one that is currently set (whatever Cesium uses as default)

    for (var i=0; i<sources.length; i++) {
        let src = sources[i];
        if (src.provider) {
            let opts = { show: false };
            if (src.render) {
                if (src.render.alphaColor) opts.colorToAlpha = Cesium.Color.fromCssColorString(src.render.alphaColor);
                if (typeof src.render.alphaColorThreshold !== 'undefined') opts.colorToAlphaThreshold = src.render.alphaColorThreshold;
            }
            src.layer = new Cesium.ImageryLayer(src.provider, opts);
            //viewerLayers.add(src.layer);
        } else {
            src.layer = defaultImageryLayer;
        }

        setLayerRendering(src);
        loadColorMap(src);
    }

    setViewerLayers();
    console.log("loaded ", sources.length, " imagery layers");
}

// TODO - this is brute force but with ad hoc added layers we get spurious effects. It probably is also more performant during zoom/pan/redraw
function setViewerLayers() {
    let viewerLayers = uiCesium.viewer.imageryLayers;
    viewerLayers.removeAll(false);
    sources.forEach( src=> {
        if (src.show) {
            viewerLayers.add(src.layer);
            src.layer.show = true;
        } else {
            src.layer.show = false;
        }
    });
}

function hideExclusives (src) {
    let exclusive = src.exclusive;
    if (exclusive && exclusive.length > 0) {
        sources.forEach( s=> {
            if ((s !== src) && util.haveEqualElements(exclusive, s.exclusive)) {
                s.show = false;
                s.layer.show = false;
                ui.updateListItem(sourceView,s);
            }
        });
    }
}

function setLayerRendering (src) {
    let layer = src.layer;
    let render = { ...defaultRender, ...src.render };

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

ui.exportToMain(function selectImgLayerSrc(event) {
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
})

ui.exportToMain(function selectImgCmapEntry(event) {
    let ce = ui.getSelectedListItem(cmView);
    ui.setTextContent("imglayer.cm.info", ce ? ce.descr : null);
});

var mouseX;
var mouseY;
let pixBuf = new Uint8Array(3);

// watch out - this runs in the render loop so avoid length computation
const probeColor = function(scene,time) {
    let canvas = scene.canvas;
    let gl = canvas.getContext('webgl');

    gl.readPixels( mouseX, gl.drawingBufferHeight - mouseY, 1, 1, gl.RGB, gl.UNSIGNED_BYTE, pixBuf);

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

ui.exportToMain(function setImgAlpha(event) {
    if (selectedSrc) {
        let v = ui.getSliderValue(event.target);
        selectedSrc.layer.alpha = v;
        uiCesium.requestRender();
    }
});

ui.exportToMain(function setImgBrightness(event) {
    let v = ui.getSliderValue(event.target);
    setImgLayerBrightness(v);
});

function setImgLayerBrightness(v) {
    if (selectedSrc) {
        selectedSrc.layer.brightness = v;
        uiCesium.requestRender();
    }
}

ui.exportToMain(function setImgContrast(event) {
    if (selectedSrc) {
        let v = ui.getSliderValue(event.target);
        selectedSrc.layer.contrast = v;
        uiCesium.requestRender();
    }
});

ui.exportToMain(function setImgHue(event) {
    let v = ui.getSliderValue(event.target);
    setImgLayerHue(v);
});

function setImgLayerHue(v) {
    if (selectedSrc) {
        selectedSrc.layer.hue = (v * Math.PI) / 180; // needs radians
        uiCesium.requestRender();
    }
}

ui.exportToMain(function setImgSaturation(event) {
    let v = ui.getSliderValue(event.target);
    setImgLayerSaturation(v);
});

function setImgLayerSaturation(v) {
    if (selectedSrc) {
        selectedSrc.layer.saturation = v;
        uiCesium.requestRender();
    }
}

ui.exportToMain(function setImgGamma(event) {
    if (selectedSrc) {
        let v = ui.getSliderValue(event.target);
        selectedSrc.layer.gamma = v;
        uiCesium.requestRender();
    }
});

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
import * as config from "./config.js";
import * as util from "./ui_util.js";
import * as ui from "./ui.js";

var tab1List = undefined;


ui.registerLoadFunction(function initialize() {
    tab1List = initTab1List();

});

function initTab1List() {
    let view = ui.getList("tabs.numbers");
    if (view) {
        ui.setListItems(view, config.tabs.numbers);
    }
    return view;
}
import * as config from "./config.js";
import * as util from "./ui_util.js";
import * as ui from "./ui.js";

var table = undefined;

ui.registerLoadFunction(function initialize() {
    table = initKvTable();
});

function initKvTable() {
    let view = ui.getKvTable("test.kv_table");
    if (view) {
        ui.setKvList(view, config.kvs.data);
    }
    return view;
}
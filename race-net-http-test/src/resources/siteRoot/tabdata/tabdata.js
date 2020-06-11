/** static test data
var fields = [ 'item_1', 'item_2', 'item_3', 'item_4', 'item_5', 'item_6', 'item_7', 'item_8', 'item_9', 'item_10' ];

var providers = [ 'provider_A','provider_B','provider_C','provider_D','provider_E','provider_F','provider_G','provider_H',
                  'provider_I','provider_J','provider_K','provider_L','provider_M','provider_N' ];

var data = {
  provider_A: { name: 'provider_A', item_1: 10, item_2: 20, item_3: 30, item_4: 40, item_5: 50, item_6: 60, item_7: 70, item_8: 80, item_9: 90, item_10: 100 },
  provider_B: { name: 'provider_B', item_1: 10, item_2: 20, item_3: 30, item_4: 40, item_5: 50, item_6: 60, item_7: 70, item_8: 80, item_9: 90, item_10: 100 },
  provider_C: { name: 'provider_C', item_1: 10, item_2: 20, item_3: 30, item_4: 40, item_5: 50, item_6: 60, item_7: 70, item_8: 80, item_9: 90, item_10: 100 },
  provider_D: { name: 'provider_D', item_1: 10, item_2: 20, item_3: 30, item_4: 40, item_5: 50, item_6: 60, item_7: 70, item_8: 80, item_9: 90, item_10: 100 },
  provider_E: { name: 'provider_E', item_1: 10, item_2: 20, item_3: 30, item_4: 40, item_5: 50, item_6: 60, item_7: 70, item_8: 80, item_9: 90, item_10: 100 },
  provider_F: { name: 'provider_F', item_1: 10, item_2: 20, item_3: 30, item_4: 40, item_5: 50, item_6: 60, item_7: 70, item_8: 80, item_9: 90, item_10: 100 },
  provider_G: { name: 'provider_G', item_1: 10, item_2: 20, item_3: 30, item_4: 40, item_5: 50, item_6: 60, item_7: 70, item_8: 80, item_9: 90, item_10: 100 },
  provider_H: { name: 'provider_H', item_1: 10, item_2: 20, item_3: 30, item_4: 40, item_5: 50, item_6: 60, item_7: 70, item_8: 80, item_9: 90, item_10: 100 },
  provider_I: { name: 'provider_I', item_1: 10, item_2: 20, item_3: 30, item_4: 40, item_5: 50, item_6: 60, item_7: 70, item_8: 80, item_9: 90, item_10: 100 },
  provider_J: { name: 'provider_J', item_1: 10, item_2: 20, item_3: 30, item_4: 40, item_5: 50, item_6: 60, item_7: 70, item_8: 80, item_9: 90, item_10: 100 },
  provider_K: { name: 'provider_K', item_1: 10, item_2: 20, item_3: 30, item_4: 40, item_5: 50, item_6: 60, item_7: 70, item_8: 80, item_9: 90, item_10: 100 },
  provider_L: { name: 'provider_L', item_1: 10, item_2: 20, item_3: 30, item_4: 40, item_5: 50, item_6: 60, item_7: 70, item_8: 80, item_9: 90, item_10: 100 },
  provider_M: { name: 'provider_M', item_1: 10, item_2: 20, item_3: 30, item_4: 40, item_5: 50, item_6: 60, item_7: 70, item_8: 80, item_9: 90, item_10: 100 },
  provider_N: { name: 'provider_N', item_1: 10, item_2: 20, item_3: 30, item_4: 40, item_5: 50, item_6: 60, item_7: 70, item_8: 80, item_9: 90, item_10: 100 }
};
**/

//--- global data set by web socket handler

var fields = [];
var providers = [];
var data = {};

//--- onLoad callback

function init() {
  setWidth(); // to properly set max div-width
  if ((fields.length > 0) && (providers.length > 0)) initTable();
  setData();

  readWebsocket();
}

//--- initialization of table structure

function initTable() {

  //--- create table header
  var thead = document.createElement('thead');
  var row = thead.insertRow(0);
  row.setAttribute("id", "column_names");

  var cell = document.createElement('th');
  row.appendChild(cell);

  for (i=0; i<providers.length; i++){
    cell = document.createElement('th');
    cell.innerHTML = providers[i];
    cell.setAttribute("onmouseenter", `selectColumn(${i+1});`);
    cell.setAttribute("onmouseleave", `unselectColumn(${i+1});`);
    row.appendChild(cell);
  }

  //--- initialize table body
  var tbody = document.createElement("tbody");
  tbody.setAttribute("id","table_body");

  for (i=0; i<fields.length; i++){
    row = document.createElement('tr');
    tbody.appendChild(row);

    cell = document.createElement('th');
    cell.innerHTML = fields[i];
    cell.setAttribute("onmouseenter", `selectRow(${i});`);
    cell.setAttribute("onmouseleave", `unselectRow(${i});`);
    row.appendChild(cell);

    for (p of providers){
      cell = document.createElement('td');
      row.appendChild(cell);
    }
  }

  var table = document.getElementById('my_table');
  while (table.firstChild){
    table.removeChild(table.firstChild);
  }
  table.appendChild(thead);
  table.appendChild(tbody);
}

function setData() {
  for (i=0; i<providers.length; i++){
    var values = data[providers[i]];
    if (values){
      setColumnData(i,values);
    }
  }
}

function setColumnData (i, values) {
  var table = document.getElementById('my_table');

  var i1 = i + 1;
  for (j=0; j<fields.length; j++){
    var v = values[fields[j]];
    if (v){
      table.rows[j+1].cells[i1].innerHTML = v;
    }
  }
}

function column (providerName) {
  for (i=0; i<providers.length; i++){
    if (providers[i] == providerName) return i;
  }
  return -1;
}

//--- column/row highlighting

function selectColumn(i) {
  var cell = document.getElementById("column_names").childNodes[i];
  cell.setAttribute("class", "selected");

  var tbody = document.getElementById("table_body");
  for (row of tbody.childNodes){
    cell = row.childNodes[i];
    cell.setAttribute("class", "selected");
  }
}

function unselectColumn(i) {
  var cell = document.getElementById("column_names").childNodes[i];
  cell.removeAttribute("class", "selected");

  var tbody = document.getElementById("table_body");
  for (row of tbody.childNodes){
    cell = row.childNodes[i];
    cell.removeAttribute("class", "selected");
  }
}

function selectRow(i) {
  var row = document.getElementById("table_body").childNodes[i];
  for (cell of row.childNodes){
    cell.setAttribute("class", "selected");
  }
}

function unselectRow(i) {
  var row = document.getElementById("table_body").childNodes[i];
  for (cell of row.childNodes){
    cell.removeAttribute("class", "selected");
  }
}

//--- dynamic data acquisition and processing

function readWebsocket() {
  if ("WebSocket" in window) {
    var ws = new WebSocket("ws://localhost:8080/tabdata/ws");

    ws.onopen = function() {
      ws.send("Hi server!");
      console.log("message is sent...");
    };

    ws.onmessage = function (evt) {
      var msg = JSON.parse(evt.data);
      var msgType = msg.msgType;

      if (msgType == 'ProviderData') {
        var providerName = msg.providerName;
        var fieldValues = msg.fieldValues;

        if (fieldValues){
          data[providerName] = fieldValues;
          if ((fields.length > 0) && (providers.length > 0)){
            var i = column(providerName);
            if (i>=0) setColumnData(i, fieldValues);
          }
        }

      } else if (msgType == "FieldCatalog") {
        var fieldNames = msg.fields;
        if (fieldNames) {
          fields = fieldNames;
          if (providers.length > 0)  {
            initTable();
            setData();
          }
        }

      } else if (msgType == "ProviderNames") {
        var providerNames = msg.providers;
        if (providerNames) {
          providers = providerNames;
          if (fields.length > 0)  {
            initTable();
            setData();
          }
        }
      }
    };

    ws.onclose = function() {
      console.log("connection is closed...");
    };
  } else {
    console.log("WebSocket NOT supported by your Browser!");
  }
}

function setWidth() {
  var newWidth = document.body.clientWidth;
  var div = document.getElementById("scroll_container");
  div.style.maxWidth = `${newWidth}px`;
}
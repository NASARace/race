
//--- global data set by web socket handler

var fields = [];
var providers = [];
var data = {}; // map providerName -> Provider{name,rev,date,fieldValues}

var modifiedFields = new Set();

var ws = {}; // will be set by initWebSocket()


//--- the part we would like to avoid (and never can)

function setBrowserSpecifics() {
  if (navigator.userAgent.search("Chrome") >= 0){
    document.getElementById("scroll_container").classList.add("chrome")
  }
}

function isEmpty(str) {
    return (!str || 0 === str.length);
}

function toUtf8Array(str) {
  var utf8 = [];
  for (var i=0; i < str.length; i++) {
    var c = str.charCodeAt(i);
    if (c < 0x80) utf8.push(c);
    else if (c < 0x800) {
      utf8.push(0xc0 | (c >> 6), 0x80 | (c & 0x3f));
    } else if (c < 0xd800 || c >= 0xe000) {
      utf8.push(0xe0 | (c >> 12), 0x80 | ((c>>6) & 0x3f),  0x80 | (c & 0x3f));
    } else {
        i++;
        c = ((c & 0x3ff)<<10) | (str.charCodeAt(i) & 0x3ff)
        utf8.push(0xf0 | (c >>18), 0x80 | ((c>>12) & 0x3f), 0x80 | ((c>>6) & 0x3f), 0x80 | (c & 0x3f));
    }
  }
  return utf8;
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

  //--- update times
  row = thead.insertRow(1);
  row.setAttribute("id", "dtgs");

  cell = document.createElement('th'); // blank
  cell.setAttribute("class", "dtg");
  row.appendChild(cell);

  for (i=0; i<providers.length; i++){
    cell = document.createElement('th');
    cell.setAttribute("class", "dtg");
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

function setFieldsEditable(fieldMap) {
  var tbody = document.getElementById('table_body');
  var hasEditableFields = false;

  for (i=0; i<providers.length; i++) {
    var provider = providers[i];
    var fieldPattern = fieldMap[provider];
    if (fieldPattern) {
      var regex = new RegExp(fieldPattern);
      for (j=0; j<fields.length; j++){
        var field = fields[j];
        if (regex.test(field)) {
          var tr = tbody.childNodes[j];
          var cell = tr.childNodes[i+1];
          var value = data[provider].fieldValues[field];
          if (!value) value = "";

          var input = document.createElement('input');
          input.setAttribute("type", "text");
          input.setAttribute("class", "field");
          input.setAttribute("onfocus", "setFieldFocused(event);");
          input.setAttribute("onblur", "setFieldBlurred(event);");
          input.setAttribute("onkeyup", "setFieldModified(event);");
          input.value = value;

          cell.innerHTML = null;
          cell.classList.add("editable");
          cell.appendChild(input);

          hasEditableFields = true;
        }
      }
    }
  }

  if (hasEditableFields) {
    document.getElementById("editButton").disabled = true;
    document.getElementById("sendButton").disabled = false;
    document.getElementById("readOnlyButton").disabled = false;
  }
}

function setData() {
  for (i=0; i<providers.length; i++){
    var provider = data[providers[i]];
    if (provider){
    console.log(provider);
      setColumnData(i,provider);
    }
  }
}

function timeString(date) {
  var h = ('0' + date.getHours()).slice(-2);
  var m = ('0' + date.getMinutes()).slice(-2);
  var s = ('0' + date.getSeconds()).slice(-2);

  return h + ':' + m + ':' + s;
}

function setColumnData (i, provider) {
  var tbody = document.getElementById('table_body');
  var trDtgs = document.getElementById('dtgs');
  var i1 = i + 1;
  var values = provider.fieldValues

  trDtgs.childNodes[i1].innerHTML = timeString(provider.date);

  for (j=0; j<fields.length; j++){
    var tr = tbody.childNodes[j];
    var v = values[fields[j]];
    var cell = tr.childNodes[i1];
    if (cell.firstChild && cell.firstChild.nodeName == "INPUT") { // we are editing this
      var input = cell.firstChild;
      if (modifiedFields.has(input)){ // we already changed it - flag conflict
        input.classList.add("conflict");
      }
      input.classList.remove("reported");
      input.value = v ? v : "";
    } else {
      cell.textContent = v ? v : "";
    }
  }
}

function column (providerName) {
  for (i=0; i<providers.length; i++){
    if (providers[i] == providerName) return i;
  }
  return -1;
}

function processCells (cellFunc){
  var tbody = document.getElementById("table_body");

  for (i=0; i<fields.length; i++) {
    var field = fields[i];
    var row = tbody.childNodes[i];
    for (j=0; j<providers.length; j++){
      var provider = providers[j];
      var cell = row.childNodes[j+1];

      cellFunc( cell, providers[j], fields[i]);
    }
  }
}


//--- column/row highlighting

function selectColumn(i) {
  var cell = document.getElementById("column_names").childNodes[i];
  cell.setAttribute("class", "selected");

  cell = document.getElementById("dtgs").childNodes[i];
  cell.classList.add("selected");

  var tbody = document.getElementById("table_body");
  for (row of tbody.childNodes){
    cell = row.childNodes[i];
    cell.setAttribute("class", "selected");
  }
}

function unselectColumn(i) {
  var cell = document.getElementById("column_names").childNodes[i];
  cell.removeAttribute("class", "selected");

  cell = document.getElementById("dtgs").childNodes[i];
  cell.classList.remove("selected");

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

function initWebSocket() {
  if ("WebSocket" in window) {
    ws = new WebSocket("ws://localhost:8080/tabdata/ws");

    ws.onopen = function() {
      console.log(`websocket ${ws} opened`);
    };

    ws.onmessage = function (evt) {
      console.log("@@@ got " + evt.data.toString());
      var msg = JSON.parse(evt.data.toString());
      var msgType = Object.keys(msg)[0];  // first member name

      if (msgType == "providerData") { // {msgType,date,<provider>{}}
        var provider = msg.providerData;
        provider.date = new Date(provider.date) // convert epoch into Date object

        if (provider.fieldValues){
          data[provider.name] = provider;
          if ((fields.length > 0) && (providers.length > 0)){
            var i = column(provider.name);
            if (i>=0) {
              setColumnData(i, provider);
            }
          }
        }

      } else if (msgType == "fieldCatalog") {  // {fields[]}
        var fieldNames = msg.fieldCatalog;
        if (fieldNames) {
          fields = fieldNames;
          if (providers.length > 0)  {
            initTable();
            setData();
          }
        }

      } else if (msgType == "providerCatalog") { // {providers[]}
        var providerNames = msg.providerCatalog;
        if (providerNames) {
          providers = providerNames;
          if (fields.length > 0)  {
            initTable();
            setData();
          }
        }

      } else if (msgType == "userPermissions") { // {uid,permissions{}}
        var usrPerm = msg.userPermissions
        var uid = usrPerm.uid; // TODO - should we check if that is the current user? Not critical since update has to be checked by server anyways
        setFieldsEditable(usrPerm.permissions);
      }
    };

    ws.onclose = function() {
      console.log(`websocket ${ws} closed`);
    };


  } else {
    console.log("WebSocket NOT supported by your Browser!");
  }
}

//--- outgoing messages

function sendRequestUserPermissions (uid,pw){
  if (ws) {
    if (isEmpty(uid) || isEmpty(pw)) {
      alert("please enter user id and password and try again");
    } else {
      ws.send(`{"requestUserPermissions":{"${uid}":[${toUtf8Array(pw).join(',')}]}}`);
    }
  }
}

function sendProviderChange (uid,pw,changeDate,provider,changedFields) {
  if (ws) {
    var msg = `{"providerChange":{"${uid}":[${toUtf8Array(pw).join(',')}],"date":${changeDate},"${provider}":{${changedFields}}}}`;
    console.log(`@@@ sending: ${msg}`);
    ws.send(msg);
  }
}

//--- browser window changes

function setWidth() {
  var newWidth = document.body.clientWidth;
  var div = document.getElementById("scroll_container");
  div.style['max-width'] = `${newWidth}px`;
}

//--- user input callbacks


function identifyUser(event) {
  if (event.key=="Enter") {
    setEditable();
  }
}

function setEditable() {
  var uid=document.getElementById("uid").value;
  var pw = document.getElementById("pw").value;
  sendRequestUserPermissions(uid,pw);
}

function setReadOnly() {
  document.getElementById("pw").value = null;
  document.getElementById("sendButton").disabled = true;
  document.getElementById("editButton").disabled = false;
  document.getElementById("readOnlyButton").disabled = true;

  processCells( (cell,provider,field) => {
    if (cell.firstChild) {
      var value = data[provider].fieldValues[field];
      cell.textContent = value; // this also removes the input child element
      cell.classList.remove("editable");
    }
  });

  modifiedFields.clear();
}

function setFieldFocused (event) {
  var input = event.target;
  input.classList.add("focused");
}

function setFieldBlurred (event) {
  var input = event.target;
  input.classList.remove("focused");
}

function setFieldModified(event) {
  var input = event.target;
  if (!input.classList.contains("modified")){
    input.classList.add("modified");

    modifiedFields.add(input);
  }
}

function sendChanges() {
  if (modifiedFields.size == 0){
    alert("no modified fields yet - nothing to send");

  } else {
    var tbody = document.getElementById("table_body");

    for (i=0; i<providers.length; i++){
      var provider = providers[i];
      var acc = "";

      for (j=0; j<fields.length; j++){
        var field = fields[j];
        var row = tbody.childNodes[j];
        var cell = row.childNodes[i+1];
        var input = cell.firstChild;
        if (modifiedFields.has(input)){
          if (acc.length > 0) acc += ",";
          acc += `"${field}":${input.value}`
          input.classList.remove("modified");
          input.classList.remove("conflict");
          input.classList.add("reported");
          modifiedFields.delete(input);
        }
      }

      if (acc.length > 0) {
        var uid=document.getElementById("uid").value;
        var pw = document.getElementById("pw").value;
        var date = Date.now();
        sendProviderChange(uid,pw,date,provider,acc);
      }
    }
  }
}

//--- onLoad callback

function init() {
  setWidth(); // to properly set max div-width
  setBrowserSpecifics();

  if ((fields.length > 0) && (providers.length > 0)) initTable();
  setData();

  initWebSocket();
}

//--- onunload callback
function shutdown() {
  if (ws) {
    ws.close();
  }
}
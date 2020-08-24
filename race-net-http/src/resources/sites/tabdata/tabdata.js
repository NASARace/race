
//--- global data set by web socket handler

var siteId = "";     // the name of the local provider

var fieldCatalog = {};      // { id:"s", info:"s", date:Date, fields:[ {id:"s",info:"s" <,header:"s">}.. ] }
var fields = [];            // fieldCatalog.fields to display

var providerCatalog = {};   // { id:"s", info:"s", date:Date, providers:[ {id:"s",info:"s",update:n}.. ] }
var providers = [];         // providerCatalog.providers to display

var data = {};              // { <providerId>: {id:"s",rev:n,date:Date,fieldValues:{<fieldId>:n,...} } }

var inEditMode = false;
var modifiedFields = new Set();

var ws = {}; // will be set by initWebSocket()

var maxLines = 0;           // we get the default from the css

//--- the part we would like to avoid (and never can)

function setBrowserSpecifics() {
  if (navigator.userAgent.search("Chrome") >= 0){
    document.getElementById("tableContainer").classList.add("chrome")
  }
}

function isEmpty(o) {
    return (!o || 0 === o.length);
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

function hasFields() {
  return (fieldCatalog && !isEmpty(fieldCatalog.fields));
}

function hasProviders() {
  return (providerCatalog && !isEmpty(providerCatalog.providers));
}

function removeAllChildren (elem) {
  while (elem.firstChild){
    elem.removeChild(elem.firstChild);
  }
}

function timeString(date) {
  var h = ('0' + date.getHours()).slice(-2);
  var m = ('0' + date.getMinutes()).slice(-2);
  var s = ('0' + date.getSeconds()).slice(-2);

  return h + ':' + m + ':' + s;
}

function log (msg) {
  var sc = document.getElementById("logContainer");
  var logEntry = document.createElement("div");

  var span = document.createElement("span");
  span.classList.add("log-time");
  span.textContent = timeString(new Date());
  logEntry.appendChild(span);

  span = document.createElement("span");
  span.classList.add("log-msg");
  span.textContent = msg;
  logEntry.appendChild(span);

  sc.insertBefore(logEntry, sc.firstChild);
}

function nameOfPath (path) {
  var i = path.lastIndexOf('/');
  if (i >= 0) {
    if (i == path.length - 1) return ''; // ends with '/' - no name
    return path.substring(i+1);
  } else {
    return path; // no path elements
  }
}

function parentOfPath (path) {
  var i = path.lastIndexOf('/');
  if (i > 0) {
    return path.substring(0,i);
  } else if (i == 0) {
    return "/";
  } else {
    return "";
  }
}

function info (msg) {
  document.getElementById("info").textContent = msg;
}

function isHeader (field) {
  return (field.attrs && field.attrs.includes("header"));
}

function isLocked (field) {
  return (field.attrs && field.attrs.includes("locked"));
}

function isHidden (field) {
  return (field.attrs && field.attrs.includes("hidden"));
}

function isComputed (field) {
  return field.hasOwnProperty("formula");
}

function siteIdIndex() {
  for (i=0; i<providers.length; i++) {
    if (providers[i].id == siteId) return i;
  }
  return -1;
}

//--- initialization of table structure

function initTable() {
  var table = document.getElementById('my_table');
  removeAllChildren(table);  // in case one of the catalogs got updated

  //table.appendChild( initColGroup());
  table.appendChild( initTHead());
  table.appendChild( initTBody());
}

function initColGroup() {
  var colGroup = document.createElement('colgroup');

  var col = document.createElement("col"); // the field id row
  col.classList.add("field");
  colGroup.appendChild(col);

  for (var i=0; i<providers.length; i++){
    var provider = providers[i];

    col = document.createElement("col");
    if (provider.id == siteId) {
      col.classList.add("local");
    }
    colGroup.appendChild(col);
  }

  return colGroup;
}

function initTHead() {
  //--- create table header
  var thead = document.createElement('thead');
  var row = thead.insertRow(0);
  row.setAttribute("id", "column_names");

  var cell = document.createElement('th');
  row.appendChild(cell);

  for (var i=0; i<providers.length; i++){
    provider = providers[i];

    cell = document.createElement('th');
    cell.setAttribute("onmouseenter", `highlightColumn(${i+1},true)`);
    cell.setAttribute("onmouseleave", `highlightColumn(${i+1},false)`);
    if (provider.id == siteId)   cell.classList.add("local");
    cell.textContent = nameOfPath(provider.id);

    row.appendChild(cell);
  }

  //--- update times
  row = thead.insertRow(1);
  row.setAttribute("id", "dtgs");

  cell = document.createElement('th'); // blank
  cell.classList.add("dtg");
  row.appendChild(cell);

  for (var i=0; i<providers.length; i++){
    provider = providers[i];

    // no content yet (will be set when we get a providerData), just create cell
    cell = document.createElement('th');
    cell.classList.add("dtg");
    cell.setAttribute("onmouseenter", `highlightColumn(${i+1},true)`);
    cell.setAttribute("onmouseleave", `highlightColumn(${i+1},false)`);
    if (provider.id == siteId)   cell.classList.add("local");
    cell.textContent = "00:00:00"

    row.appendChild(cell);
  }

  return thead;
}

function initTBody () {
  var tbody = document.createElement("tbody");
  tbody.setAttribute("id","table_body");

  for (var i=0; i<fields.length; i++){
    var field = fields[i];
    var level = fieldLevel(field.id);
    tbody.appendChild( initFieldRow(field, i, level-1));
  }
  return tbody;
}

function initFieldRow (field, idx, stripLevel) {
  var row = document.createElement('tr');

  if (isHeader(field)) {
    row.classList.add("header");
  }

  if (isComputed(field)) {
    row.classList.add("computed");
  }

  cell = document.createElement('th');
  cell.classList.add("tooltip");
  cell.setAttribute("onmouseenter", `highlightRow(${idx},true)`);
  cell.setAttribute("onmouseleave", `highlightRow(${idx},false)`);
  cell.setAttribute("onclick", `clickRow(${idx})`);

  var fieldLabel = field.id;
  if (stripLevel > 0) {
    cell.style.paddingLeft = `${stripLevel*2}rem`;
    fieldLabel = stripLevels(field.id,stripLevel);
  }
  if (field.isCollapsed) fieldLabel += " …";
  cell.textContent = fieldLabel;

  row.appendChild(cell);

  for (var p of providers){
    // no data yet, will be set when we get a providerData message
    cell = document.createElement('td');
    if (p.id == siteId) cell.classList.add("local");
    row.appendChild(cell);
  }
  return row;
}

function fieldLevel (id) {
  var level = 1;
  var len = id.length;
  if (len > 1) {
    var i = id[0] == '/' ? 1 : 0;   // leading '/' don't count
    if (id[len-1] == '/') len--; // trailing '/' don't count
    for (; i < len; i++) {
      if (id[i] == '/') level++;
    }
  }
  return level;
}

function stripLevels (id, n) {
  var s = id[0] == '/' ? id.substring(1) : id; // we don't count the root as separate level
  for (; n>0; n--) {
    var i = s.indexOf('/');
    if (i < 0) return null;
    s = s.substring(i+1);
  }
  return s;
}

function checkEditableCell (cell,provider,providerPatterns,field,fieldPatterns) {
  var i;
  for (var i=0; i<providerPatterns.length;i++) {  // note there can be more than one providerPattern match
    if (providerPatterns[i].test(provider.id)) {
      if (fieldPatterns[i].test(field.id)) {
        var value = formatValue( field, data[provider.id].fieldValues[field.id]);

        var input = document.createElement('input');
        input.setAttribute("type", "text");
        input.setAttribute("class", "field");
        input.setAttribute("onfocus", "setFieldFocused(event)");
        input.setAttribute("onblur", "setFieldBlurred(event)");
        input.setAttribute("onkeyup", "setFieldModified(event)");
        input.value = value;

        cell.innerHTML = null;
        cell.appendChild(input);
        return true; // we only set it editable once
      }
    }
  }
  return false;
}

function setFieldsEditable (permissions) {
  var tbody = document.getElementById('table_body');
  var providerPatterns = Object.keys(permissions).map( p => new RegExp(p));
  var fieldPatterns = Object.values(permissions).map( p => new RegExp(p));
  var hasEditableFields = false;

  for (var i=0; i<providers.length; i++) {
    var provider = providers[i];
    if (providerPatterns.find( r=> r.test(provider.id))) { // only iterate over fields for providers that have at least one match
      for (var j=0; j<fields.length; j++){
        var field = fields[j];
        if (!isLocked(field)) {
          var tr = tbody.childNodes[j];
          var cell = tr.childNodes[i+1];
          if (checkEditableCell(cell,provider,providerPatterns,field,fieldPatterns)) hasEditableFields = true;
        }
      }
    }
  }

  if (hasEditableFields) {
    document.getElementById("editButton").disabled = true;
    document.getElementById("sendButton").disabled = false;
    document.getElementById("readOnlyButton").disabled = false;
    inEditMode = true;
  }
}

function setData() {
  if (providers.length > 0 && fields.length > 0) {
    for (var i=0; i<providers.length; i++){
      var provider = data[providers[i].id];
      if (provider){
        setColumnData(i,provider);
      }
    }
  }
}

function checkRange (field,value,cell) {
  if (outsideRange(field,value)){
    cell.classList.add("alert");
  } else {
    cell.classList.remove("alert");
  }
}

function setColumnData (i, providerData) {
  var tbody = document.getElementById('table_body');
  var trDtgs = document.getElementById('dtgs');
  var i1 = i + 1;
  var values = providerData.fieldValues;

  trDtgs.childNodes[i1].textContent = timeString(providerData.date);

  for (var j=0; j<fields.length; j++){
    var field = fields[j];
    var tr = tbody.childNodes[j];
    var v = displayValue( field, fieldCatalog.fields, values);
    var cell = tr.childNodes[i1];

    if (cell.firstChild && cell.firstChild.nodeName == "INPUT") { // input cell - we are editing this
      var input = cell.firstChild;
      if (modifiedFields.has(input)){ // we already changed it - flag conflict
        input.classList.add("conflict");
      }
      input.classList.remove("reported");
      input.value = v;

    } else { // just a display cell but flag values outside range
      cell.textContent = v;
      checkRange(field,values[field.id],cell);
    }
  }
}

function outsideRange (field, fieldValue) {
  if (field.min && fieldValue < field.min) return true;
  if (field.max && fieldValue > field.max) return true;
}

function formatValue (field,fv) {
  if (fv == undefined) return "";
  var v = fv.value;
  if (field.type == "rational" && Number.isInteger(v)) return v.toFixed(1);
  return v;
}

function displayValue (field, fieldList, fieldValues) {
  return formatValue(field, fieldValues[field.id]);
}

function column (providerName) {
  for (var i=0; i<providers.length; i++){
    if (providers[i].id == providerName) return i;
  }
  return -1;
}

function processCells (cellFunc){
  var tbody = document.getElementById("table_body");

  for (var i=0; i<fields.length; i++) {
    var field = fields[i];
    var row = tbody.childNodes[i];
    for (var j=0; j<providers.length; j++){
      var provider = providers[j];
      var cell = row.childNodes[j+1];

      cellFunc( cell, provider, field);
    }
  }
}

function filterFields () {
  var pattern = document.getElementById("fieldFilter").value;
  if (pattern) {
    var regex = new RegExp(pattern);
    return fieldCatalog.fields.filter( f => regex.test(f.id) && !isHidden(f));
  } else {
    return fieldCatalog.fields.filter( f => !isHidden(f))
  }
}

function fieldIdPrefix (field) {
  if (field.id.endsWith('/')) {
    return field.id;
  } else {
    return field.id + '/';
  }
}

function expandField (i) {
  var field = fields[i];
  var idPrefix = fieldIdPrefix(field);
  var k = i+1;

  if (field.isCollapsed) { // expand - add all children
    var allFields = fieldCatalog.fields;
    for ( var j = allFields.indexOf(field)+1; j < allFields.length; j++){
      var f = allFields[j];
      if (f.id.startsWith(idPrefix)) {
        fields.splice(k,0,f);
        k += 1;
      } else break;
    }
    field.isCollapsed = false;

  } else { // collapse - remove all children
    while (k < fields.length && fields[k].id.startsWith(idPrefix)){
      fields.splice(k,1);
    }
    field.isCollapsed = true;
  }

  initTable();
  setData();
}


function filterProviders () {
  var pattern = document.getElementById("providerFilter").value;
  if (pattern) {
    var regex = new RegExp(pattern);
    return providerCatalog.providers.filter( p => regex.test(p.id))
  } else {
    return providerCatalog.providers;
  }
}

//--- column/row highlighting

function highlightColumn (i, isSelected) {
  var tbody = document.getElementById("table_body");
  var idCell = document.getElementById("column_names").childNodes[i];
  var dtgCell = document.getElementById("dtgs").childNodes[i];

  if (isSelected) {
    idCell.classList.add("selected");
    dtgCell.classList.add("selected");
    for (var row of tbody.childNodes) {
      row.childNodes[i].classList.add("selected");
    }
    showProviderInfo(providers[i-1]);
  } else {
    idCell.classList.remove("selected");
    dtgCell.classList.remove("selected");
    for (var row of tbody.childNodes) {
      row.childNodes[i].classList.remove("selected");
    }
    info(null);
  }
}

function showProviderInfo (provider) {
  var txt = provider.info;
  info(txt);
}

function highlightRow (i, isSelected) {
  var row = document.getElementById("table_body").childNodes[i];
  if (isSelected) {
    for (var cell of row.childNodes) {
      cell.classList.add( "selected");
    }
    showFieldInfo(fields[i]);
  } else {
    for (var cell of row.childNodes) {
      cell.classList.remove("selected");
    }
    info(null);
  }
}

function showFieldInfo (field) {
  var txt = field.info;
  if (field.min || field.max){
    txt += ".         [";
    if (field.min) txt += field.min;
    txt += ' , '
    if (field.max) txt += field.max;
    txt += ']';
  }
  if (field.formula) {
    txt += ".         =";
    txt += field.formula;
  }
  info(txt);
}

function clickRow (i) {
  if (isHeader(fields[i])) {
    expandField(i);
  }
}


//--- dynamic data acquisition and processing

function getWsUrl() {
  var loc = document.location;
  var prot = (loc.protocol == "https:") ? "wss://" : "ws://";

  return (prot + loc.host + parentOfPath(loc.pathname) + "/ws");
}

function initWebSocket() {
  if ("WebSocket" in window) {
    var wsUrl = getWsUrl();
    //console.log("@@@ wsUrl = " + wsUrl);
    ws = new WebSocket(wsUrl);

    ws.onopen = function() {
      console.log(`websocket ${ws} opened`);
    };

    ws.onmessage = function (evt) {
      //console.log("got " + evt.data.toString());
      var msg = JSON.parse(evt.data.toString());
      var msgType = Object.keys(msg)[0];  // first member name

      if (msgType == "providerData") {   // {providerData:{id:"s",rev:n,date:n,fieldValues:[<fieldName>:n]}
        var providerData = msg.providerData;
        providerData.date = new Date(providerData.date) // convert epoch into Date object

        if (providerData.fieldValues){
          if (data[providerData.id]) log(`${providerData.id} data updated`);

          data[providerData.id] = providerData;
          if ((fields.length > 0) && (providers.length > 0)){
            var i = column(providerData.id);
            if (i>=0) {
              setColumnData(i, providerData);
            }
          }
        }

      }
      else if (msgType == "fieldCatalog") {  // {fieldCatalog:{rev:n,fields:[{id:"s",info:"s" <,header:"s">}..]}}
        var catalog = msg.fieldCatalog;
        if (catalog) {
          fieldCatalog = catalog;
          fields = filterFields();

          document.getElementById("datasetTitle").textContent = `${fieldCatalog.id} : ${fieldCatalog.info}`
          if (hasProviders())  {
            initTable();
            setData();
          }
        }

      }
      else if (msgType == "providerCatalog") { // {providerCatalog:{ rev:n, providers:[ {id:"s",info:"s"}.. ]}}
        var catalog = msg.providerCatalog;
        if (catalog) {
          providerCatalog = catalog;
          providers = filterProviders();

          document.getElementById("sourceTitle").textContent = `${providerCatalog.id} : ${providerCatalog.info}`
          if (hasFields())  {
            initTable();
            setData();
          }
        }

      }
      else if (msgType == "userPermissions") { // {uid,permissions{}}
        var usrPerm = msg.userPermissions
        console.log(JSON.stringify(usrPerm));
        var uid = usrPerm.uid; // TODO - should we check if that is the current user? Not critical since update has to be checked by server anyways
        if (usrPerm.permissions) {
          setFieldsEditable(usrPerm.permissions);
          log(`enter edit mode for user ${uid}`)
        } else {
          log(`reject edit mode for user ${uid}`);
        }
      }
      else if (msgType == "siteId") {
        siteId = msg.siteId;
        document.getElementById("siteId").textContent = siteId
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

function sendUserChange (uid,pw,changeDate,provider,changedFields) {
  if (ws) {
    var msg = `{"userChange":{"${uid}":[${toUtf8Array(pw).join(',')}],"date":${changeDate},"providerCatalogId":"${providerCatalog.id}","fieldCatalogId":"${fieldCatalog.id}","siteId":"${siteId}","${provider.id}":{${changedFields}}}}`;
    ws.send(msg);
    log(`sent data changes for ${provider.id}`);
  }
}

//--- browser window changes

function setWidth() {
  var newWidth = document.body.clientWidth;
  var div = document.getElementById("tableContainer");
  div.style['max-width'] = `${newWidth}px`;
}

//--- user input callbacks


function identifyUser(event) {
  if (event.key=="Enter") {
    setEditable();
  }
}

function setEditable() {
  if (inEditMode) {
    alert("please set readOnly before requesting new user permissions");

  } else {
    var uid = document.getElementById("uid").value;
    var pw = document.getElementById("pw").value;

    if (isEmpty(uid) || isEmpty(pw)) {
      alert("please enter user credentials before requesting edit permissions");
    } else {
      sendRequestUserPermissions(uid,pw);
    }
  }
}

function setReadOnly() {
  document.getElementById("pw").value = null;
  document.getElementById("sendButton").disabled = true;
  document.getElementById("editButton").disabled = false;
  document.getElementById("readOnlyButton").disabled = true;

  processCells( (cell,provider,field) => {
    if (cell.firstChild) {
      var values = data[provider.id].fieldValues;
      var v = displayValue( field, fieldCatalog.fields, values);
      cell.textContent = v; // this also removes the input child element
      checkRange(field,values[field.id],cell);
    }
  });

  modifiedFields.clear();
  inEditMode = false;
  log("exit edit mode");
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
    var uid=document.getElementById("uid").value;
    var pw = document.getElementById("pw").value;

    if (isEmpty(uid) || isEmpty(pw)) {
      alert("please enter user credentials before sending changes");

    } else {
      var tbody = document.getElementById("table_body");

      for (var i=0; i<providers.length; i++){
        var provider = providers[i];
        var acc = "";

        for (var j=0; j<fields.length; j++){
          var field = fields[j];
          var row = tbody.childNodes[j];
          var cell = row.childNodes[i+1];
          var input = cell.firstChild;
          if (modifiedFields.has(input)){
            if (acc.length > 0) acc += ",";
            acc += `"${field.id}":${input.value}`
            input.classList.remove("modified");
            input.classList.remove("conflict");
            input.classList.add("reported");
            modifiedFields.delete(input);
          }
        }

        if (acc.length > 0) {
          sendUserChange(uid,pw,Date.now(),provider,acc);
        }
      }
    }
  }
}

function setFilters () {
  providers = filterProviders();
  fields = filterFields();

  initTable();
  setData();
}

function clearFilters () {
  providers = providerCatalog.providers;
  document.getElementById("providerFilter").value = "";

  fields = fieldCatalog.fields;
  document.getElementById("fieldFilter").value = "";

  initTable();
  setData();
}

function setDisplayLines () {
  var nLines = document.getElementById("lines").value;
  if (nLines && displayLines != nLines) {
    maxLines = nLines;
    document.body.style.setProperty("--max-lines",maxLines);
  }
}

function enterDisplayLines () {
  if (event.key=="Enter") setDisplayLines();
}

function checkOutdatedProviders() {
  console.log("@@ check outdated")
}

function setCheckInterval () {
  var v = document.getElementById("checkInterval").value;
  var checkInterval = parseInt(v);
  if (checkInterval && checkInterval > 0) {
    setInterval( checkOutdatedProviders, checkInterval * 1000);
  } else {
    clearInterval();
  }
}

function enterCheckInterval (event) {
  if (event.key=="Enter") setCheckInterval();
}

function setDisplay () {
  setDisplayLines();
  setCheckInterval();
}

function resetDisplay () {
}

//--- onLoad callback

function init() {
  setWidth(); // to properly set max div-width
  setBrowserSpecifics();

  initDefaultValues();

  if (hasFields() && hasProviders()){
     initTable();
     setData();
  }

  initWebSocket();
  log("view initialized");
}

function initDefaultValues (e) {
  maxLines = parseInt(getComputedStyle(document.body).getPropertyValue("--max-lines"));
  document.getElementById("lines").value = maxLines;
}

//--- onunload callback
function shutdown() {
  if (ws) {
    ws.close();
  }
}
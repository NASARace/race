
//--- global data set by web socket handler

var fieldCatalog = {};      // { rev:n, fields:[ {id:"s",info:"s" <,header:"s">}.. ] }
var fields = [];            // fieldCatalog.fields to display

var providerCatalog = {};   // { rev:n, providers:[ {id:"s",info:"s"}.. ] }
var providers = [];         // providerCatalog.providers to display

var data = {};              // { <providerId>: {id:"s",rev:n,date:Date,fieldValues:{<fieldId>:n,...} } }

var inEditMode = false;
var modifiedFields = new Set();

var ws = {}; // will be set by initWebSocket()


//--- the part we would like to avoid (and never can)

function setBrowserSpecifics() {
  if (navigator.userAgent.search("Chrome") >= 0){
    document.getElementById("scroll_container").classList.add("chrome")
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

//--- initialization of table structure

function initTable() {
  var table = document.getElementById('my_table');
  removeAllChildren(table);  // in case one of the catalogs got updated

  table.appendChild( initTHead());
  table.appendChild( initTBody());
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
    cell.textContent = provider.id;
    row.appendChild(cell);
  }

  //--- update times
  row = thead.insertRow(1);
  row.setAttribute("id", "dtgs");

  cell = document.createElement('th'); // blank
  cell.setAttribute("class", "dtg");
  row.appendChild(cell);

  for (var i=0; i<providers.length; i++){
    // no content yet (will be set when we get a providerData), just create cell
    cell = document.createElement('th');
    cell.setAttribute("class", "dtg");
    cell.setAttribute("onmouseenter", `highlightColumn(${i+1},true)`);
    cell.setAttribute("onmouseleave", `highlightColumn(${i+1},false)`);
    cell.textContent = "00:00:00"
    row.appendChild(cell);
  }

  return thead;
}

function initTBody () {
  var tbody = document.createElement("tbody");
  tbody.setAttribute("id","table_body");

  var stripLevel = 0;

  for (var i=0; i<fields.length; i++){
    var field = fields[i];
    var level = fieldLevel(field.id);

    if (level <= stripLevel)  stripLevel = level - 1;
    tbody.appendChild( initFieldRow(field, i, stripLevel));
    if (field.header)  stripLevel = level;
  }
  return tbody;
}

function initFieldRow (field, idx, stripLevel) {
  var fieldHeader = field.header;

  var row = document.createElement('tr');

  if (fieldHeader) {
    row.classList.add("header");
    row.setAttribute("headerType", fieldHeader);
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
        var value = data[provider.id].fieldValues[field.id];
        if (!value) value = "";

        var input = document.createElement('input');
        input.setAttribute("type", "text");
        input.setAttribute("class", "field");
        input.setAttribute("onfocus", "setFieldFocused(event)");
        input.setAttribute("onblur", "setFieldBlurred(event)");
        input.setAttribute("onkeyup", "setFieldModified(event)");
        input.value = value;

        cell.innerHTML = null;
        cell.classList.add("editable");
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
        if (!field.header) {
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

function timeString(date) {
  var h = ('0' + date.getHours()).slice(-2);
  var m = ('0' + date.getMinutes()).slice(-2);
  var s = ('0' + date.getSeconds()).slice(-2);

  return h + ':' + m + ':' + s;
}

function setColumnData (i, providerData) {
  var tbody = document.getElementById('table_body');
  var trDtgs = document.getElementById('dtgs');
  var i1 = i + 1;
  var values = providerData.fieldValues

  trDtgs.childNodes[i1].textContent = timeString(providerData.date);

  for (var j=0; j<fields.length; j++){
    var tr = tbody.childNodes[j];
    var v = values[fields[j].id];
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
    return fieldCatalog.fields.filter( f => regex.test(f.id));
  } else {
    return Array.from(fieldCatalog.fields);  // make sure we don't modify the original
  }
}

function expandField (i) {
  var field = fields[i];
  var idPrefix = field.id;
  if (!idPrefix.endsWith('/')) idPrefix += '/';
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

function showEditMessage (txt) {
  document.getElementById("msg").textContent = txt;
}

//--- column/row highlighting

function highlightColumn (i, isSelected) {
  var tbody = document.getElementById("table_body");
  var idCell = document.getElementById("column_names").childNodes[i];
  var dtgCell = document.getElementById("dtgs").childNodes[i];

  if (isSelected) {
    idCell.classList.add("selected");
    dtgCell.classList.add("selected");
    for (var row of tbody.childNodes) row.childNodes[i].classList.add("selected")
    document.getElementById("info").textContent = providers[i-1].info;
  } else {
    idCell.classList.remove("selected");
    dtgCell.classList.remove("selected");
    for (var row of tbody.childNodes) row.childNodes[i].classList.remove("selected")
    document.getElementById("info").textContent = null;
  }
}

function highlightRow (i, isSelected) {
  var row = document.getElementById("table_body").childNodes[i];
  if (isSelected) {
    for (var cell of row.childNodes) cell.setAttribute("class", "selected");
    document.getElementById("info").textContent = fields[i].info;
  } else {
    for (var cell of row.childNodes) cell.removeAttribute("class", "selected");
    document.getElementById("info").textContent = null;
  }
}

function clickRow (i) {
  if (fields[i].header) {
    expandField(i);
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
      //console.log("got " + evt.data.toString());
      var msg = JSON.parse(evt.data.toString());
      var msgType = Object.keys(msg)[0];  // first member name

      if (msgType == "providerData") {   // {providerData:{id:"s",rev:n,date:n,fieldValues:[<fieldName>:n]}
        var providerData = msg.providerData;
        providerData.date = new Date(providerData.date) // convert epoch into Date object

        if (providerData.fieldValues){
          data[providerData.id] = providerData;
          if ((fields.length > 0) && (providers.length > 0)){
            var i = column(providerData.id);
            if (i>=0) {
              setColumnData(i, providerData);
            }
          }
        }

      } else if (msgType == "fieldCatalog") {  // {fieldCatalog:{rev:n,fields:[{id:"s",info:"s" <,header:"s">}..]}}
        var catalog = msg.fieldCatalog;
        if (catalog) {
          fieldCatalog = catalog;
          fields = filterFields();
          if (hasProviders())  {
            initTable();
            setData();
          }
        }

      } else if (msgType == "providerCatalog") { // {providerCatalog:{ rev:n, providers:[ {id:"s",info:"s"}.. ]}}
        var catalog = msg.providerCatalog;
        if (catalog) {
          providerCatalog = catalog;
          providers = filterProviders();
          if (hasFields())  {
            initTable();
            setData();
          }
        }

      } else if (msgType == "userPermissions") { // {uid,permissions{}}
        var usrPerm = msg.userPermissions
        console.log(JSON.stringify(usrPerm));
        var uid = usrPerm.uid; // TODO - should we check if that is the current user? Not critical since update has to be checked by server anyways
        if (usrPerm.permissions) {
          setFieldsEditable(usrPerm.permissions);
        } else {
          showEditMessage("user has no edit permissions");
        }
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
    var msg = `{"providerChange":{"${uid}":[${toUtf8Array(pw).join(',')}],"date":${changeDate},"${provider.id}":{${changedFields}}}}`;
    ws.send(msg);
  }
}

//--- browser window changes

function setWidth() {
  var newWidth = document.body.clientWidth;
  var div = document.getElementById("scrollContainer");
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
      var value = data[provider.id].fieldValues[field.id];
      cell.textContent = value; // this also removes the input child element
      cell.classList.remove("editable");
    }
  });

  modifiedFields.clear();
  inEditMode = false;
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
          sendProviderChange(uid,pw,Date.now(),provider,acc);
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

//--- onLoad callback

function init() {
  setWidth(); // to properly set max div-width
  setBrowserSpecifics();

  if (hasFields() && hasProviders()){
     initTable();
     setData();
  }

  initWebSocket();
}

//--- onunload callback
function shutdown() {
  if (ws) {
    ws.close();
  }
}
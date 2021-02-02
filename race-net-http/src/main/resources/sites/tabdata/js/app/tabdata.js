import { initWebSocket } from './websocket.js';
import * as glob from './glob.js';
import * as utils from './utils.js';

/**
  * this is the main tabdata application module which holds and manipulates the data model
  */

//--- module globals (not exported)

var siteId = "";     // the name of the local column

var rowList = {};      // { id:"s", info:"s", date:Date, rows:[ {id:"s",info:"s" <,header:"s">}.. ] }
var rows = [];            // rowList.rows to display

var columnList = {};   // { id:"s", info:"s", date:Date, columns:[ {id:"s",info:"s",update:n}.. ] }
var columns = [];         // columnList.columns to display

var data = {};              // { <columnId>: {id:"s",rev:n,date:Date,rows:{<rowId>:n,...} } }

var inEditMode = false; var modifiedRows = new Set();

var ws = {}; // will be set by initWebSocket()

var maxLines = 0;           // we get the default from the css

var maxIdleEditMillis = 150000;  // after which we switch back into r/o mode

//--- the number formatters to use for display
var intFormatter = Intl.NumberFormat(utils.language());
var intRatFormatter = Intl.NumberFormat(utils.language(),{minimumFractionDigits:1});
var ratFormatter = Intl.NumberFormat(utils.language(),{compactFormat: "short"}); // the default number formatter
var ratIntFormatter = Intl.NumberFormat(utils.language(),{maximumFractionDigits:0});


//--- exported functions (used by main module)

export function initTabData() {
  setWidth(); // to properly set max div-width
  setBrowserSpecifics();

  initDefaultValues();

  if (hasRows() && hasColumns()){
     initTable();
     setData();
  }

  ws = initWebSocket(handleWsOpen, handleWsMessage, handleWsClose);
  utils.log("view initialized");
}

export function shutdownTabData() {
  if (ws) {
    ws.close();
  }
}

export function setEditable() {
  if (inEditMode) {
    alert("please set readOnly before requesting new user permissions");

  } else {
    var uid = document.getElementById("uid").value;
    var pw = document.getElementById("pw").value;

    if (utils.isEmpty(uid) || utils.isEmpty(pw)) {
      alert("please enter user credentials before requesting edit permissions");
    } else {
      sendRequestUserPermissions(uid,pw);
    }
  }
}

export function setReadOnly() {
  setInactiveChecks(null);

  document.getElementById("pw").value = null;
  document.getElementById("sendButton").disabled = true;
  document.getElementById("editButton").disabled = false;
  document.getElementById("readOnlyButton").disabled = true;

  processCells( (cell,column,row) => {
    if (cell.firstChild) {
      var values = data[column.id].rows;
      var v = displayValue( row, rowList.rows, values);
      cell.textContent = v; // this also removes the input child element
    }
  });

  modifiedRows.clear();
  inEditMode = false;
  utils.log("exit edit mode");
}

export function sendChanges() {
  if (modifiedRows.size == 0){
    alert("no modified rows yet - nothing to send");

  } else {
    var uid=document.getElementById("uid").value;
    var pw = document.getElementById("pw").value;

    if (utils.isEmpty(uid) || utils.isEmpty(pw)) {
      alert("please enter user credentials before sending changes");

    } else {
      var tbody = document.getElementById("table_body");

      for (var i=0; i<columns.length; i++){
        var column = columns[i];
        var acc = "";

        for (var j=0; j<rows.length; j++){
          var row = rows[j];
          var tr = tbody.childNodes[j];
          var cell = tr.childNodes[i+1];
          var input = cell.firstChild;
          if (modifiedRows.has(input)){
            if (acc.length > 0) acc += ",";
            acc += `"${row.id}":${input.value}`
            input.classList.remove("modified");
            input.classList.remove("conflict");
            input.classList.add("reported");
            modifiedRows.delete(input);
          }
        }

        if (acc.length > 0) {
          sendUserChange(uid,pw,Date.now(),column,acc);
        }
      }
    }
  }
}

export function setFilters () {
  columns = filterColumns();
  rows = displayRows(filterRows());

  initTable();
  setData();
}

export function clearFilters () {
  columns = columnList.columns;
  document.getElementById("columnFilter").value = "";

  rows = displayRows(rowList.rows);
  document.getElementById("rowFilter").value = "";

  initTable();
  setData();
}

export function setWidth() {
  var newWidth = document.body.clientWidth;
  var div = document.getElementById("tableContainer");
  div.style['max-width'] = `${newWidth}px`;
}

export function setCheckInterval () {
  var v = document.getElementById("checkInterval").value;
  var checkInterval = parseInt(v);
  if (checkInterval && checkInterval > 0) {
    setInterval( checkOutdatedColumns, checkInterval * 1000);
  } else {
    clearInterval();
  }
}

export function setDisplayLines () {
  var nLines = document.getElementById("lines").value;
  if (nLines && displayLines != nLines) {
    maxLines = nLines;
    document.body.style.setProperty("--max-lines",maxLines);
  }
}

//--- internal functions

function setBrowserSpecifics() {
  if (navigator.userAgent.search("Chrome") >= 0){
    document.getElementById("tableContainer").classList.add("chrome")
  }
}

function initDefaultValues (e) {
  maxLines = parseInt(getComputedStyle(document.body).getPropertyValue("--max-lines"));
  document.getElementById("lines").value = maxLines;
}

function hasRows() {
  return (rowList && !utils.isEmpty(rowList.rows));
}

function hasColumns() {
  return (columnList && !utils.isEmpty(columnList.columns));
}

function isHeader (row) {
  return (row.attrs && row.attrs.includes("header"));
}

function isLocked (row) {
  return (row.attrs && row.attrs.includes("locked"));
}

function isHidden (row) {
  return (row.attrs && row.attrs.includes("hidden"));
}

function isComputed (row) {
  return row.hasOwnProperty("formula");
}

function isSiteColumn (col) {
  return (col.id == siteId || col.node == siteId);
}

function siteIdIndex() {
  for (i=0; i<columns.length; i++) {
    if (columns[i].id == siteId) return i;
  }
  return -1;
}

//--- initialization of table structure

function initTable() {
  var table = document.getElementById('my_table');
  utils.removeAllChildren(table);  // in case one of the catalogs got updated

  //table.appendChild( initColGroup());
  table.appendChild( initTHead());
  table.appendChild( initTBody());
}

function initColGroup() {
  var colGroup = document.createElement('colgroup');

  var col = document.createElement("col"); // the row id row
  col.classList.add("row");
  colGroup.appendChild(col);

  for (var i=0; i<columns.length; i++){
    var column = columns[i];

    col = document.createElement("col");
    if (column.id == siteId) {
      col.classList.add("local");
    }
    colGroup.appendChild(col);
  }

  return colGroup;
}

function initTHead() {
  //--- create table header
  var thead = document.createElement('thead');
  thead.setAttribute('id', 'table_head');

  thead.appendChild(createInfoRow());
  thead.appendChild(createColumnNameRow());
  thead.appendChild(createColumnUpdateRow());

  return thead;
}

function createInfoRow () {
  var row = document.createElement('tr');

  var cell = document.createElement('th');
  cell.classList.add("info","label");
  cell.textContent = "selected";
  row.appendChild(cell);

  cell = document.createElement('th');
  cell.setAttribute("id", "info");
  cell.classList.add('info');
  cell.setAttribute('colspan', columns.length);
  row.appendChild(cell);

  return row;
}

function createColumnNameRow () {
  var row = document.createElement('tr');
  row.setAttribute("id", "column_names");

  var cell = document.createElement('th');
  cell.classList.add("name","label");
  row.appendChild(cell); // the row-id column

  for (var i=0; i<columns.length; i++){
    var column = columns[i];

    cell = document.createElement('th');
    cell.classList.add("name");
    if (isSiteColumn(column))   cell.classList.add("local");

    cell.setAttribute("onmouseenter", `main.highlightColumn(${i+1},true)`);
    cell.setAttribute("onmouseleave", `main.highlightColumn(${i+1},false)`);
    cell.textContent = utils.nameOfPath(column.id);

    row.appendChild(cell);
  }
  return row;
}

function createColumnUpdateRow () {
  var row = document.createElement('tr');
  row.setAttribute("id", "column_dtgs");

  var cell = document.createElement('th'); // blank
  cell.classList.add("dtg","label");
  cell.textContent = "update"
  row.appendChild(cell);

  for (var i=0; i<columns.length; i++){
    var column = columns[i];

    // no content yet (will be set when we get a columnData), just create cell
    cell = document.createElement('th');
    cell.classList.add("dtg");
    if (isSiteColumn(column))   cell.classList.add("local");

    cell.setAttribute("onmouseenter", `main.highlightColumn(${i+1},true)`);
    cell.setAttribute("onmouseleave", `main.highlightColumn(${i+1},false)`);
    cell.textContent = "00:00:00"

    row.appendChild(cell);
  }
  return row;
}

function initTBody () {
  var tbody = document.createElement("tbody");
  tbody.setAttribute("id","table_body");

  for (var i=0; i<rows.length; i++){
    tbody.appendChild( initRow(rows[i], i));
  }
  return tbody;
}

function initRow (row, idx) {
  var tr = document.createElement('tr');

  if (isHeader(row)) {
    tr.classList.add("header");
  }

  if (isComputed(row)) {
    tr.classList.add("computed");
  }

  var cell = document.createElement('th');
  cell.classList.add("tooltip");
  cell.setAttribute("onmouseenter", `main.highlightRow(${idx},true)`);
  cell.setAttribute("onmouseleave", `main.highlightRow(${idx},false)`);
  cell.setAttribute("onclick", `main.clickRow(${idx})`);

  if (row.level > 0) {
    cell.style.paddingLeft = `${row.level}rem`;
  }
  var rowLabel = row.label;
  if (row.isCollapsed) rowLabel += " …";
  cell.textContent = rowLabel;

  tr.appendChild(cell);

  for (var p of columns){
    // no data yet, will be set when we get a columnData message
    cell = document.createElement('td');
    if (isSiteColumn(p)) cell.classList.add("local");
    tr.appendChild(cell);
  }
  return tr;
}

function checkEditableCell (cell,column,columnPatterns,row,rowPatterns) {
  var i;
  for (var i=0; i<columnPatterns.length;i++) {  // note there can be more than one columnPattern match
    if (columnPatterns[i].test(column.id)) {
      if (rowPatterns[i].test(row.id)) {
        //var value = formatValue( row, data[column.id].rows[row.id]);
        var value = editValue( row, data[column.id].rows[row.id]);

        var input = document.createElement('input');
        input.setAttribute("type", "text");
        input.setAttribute("class", "cell");
        input.setAttribute("onfocus", "main.setRowFocused(event)");
        input.setAttribute("onblur", "main.setRowBlurred(event)");
        input.setAttribute("onkeyup", "main.setRowModified(event)");
        input.value = value;

        cell.innerHTML = null;
        cell.appendChild(input);
        return true; // we only set it editable once
      }
    }
  }
  return false;
}

function setRowsEditable (permissions) {
  var tbody = document.getElementById('table_body');
  var columnPatterns = Object.keys(permissions).map( p => glob.glob2regexp(p));
  var rowPatterns = Object.values(permissions).map( p => glob.glob2regexp(p));
  var hasEditableRows = false;

  for (var i=0; i<columns.length; i++) {
    var column = columns[i];
    if (columnPatterns.find( r=> r.test(column.id))) { // only iterate over rows for columns that have at least one match
      for (var j=0; j<rows.length; j++){
        var row = rows[j];
        if (!isLocked(row)) {
          var tr = tbody.childNodes[j];
          var cell = tr.childNodes[i+1];
          if (checkEditableCell(cell,column,columnPatterns,row,rowPatterns)) hasEditableRows = true;
        }
      }
    }
  }

  if (hasEditableRows) {
    document.getElementById("editButton").disabled = true;
    document.getElementById("sendButton").disabled = false;
    document.getElementById("readOnlyButton").disabled = false;
    inEditMode = true;
    setInactiveChecks(setReadOnly,maxIdleEditMillis);
  }
}

function setInactiveChecks (action,maxIdleMillis = 150000) {
  var time;

  if (action) {
    document.addEventListener('mousemove', resetIdleTimer);
    document.addEventListener('keypress', resetIdleTimer);
    document.getElementById("tableContainer").addEventListener('scroll', resetIdleTimer);
  } else {
    document.removeEventListener('mousemove', resetIdleTimer);
    document.removeEventListener('keypress', resetIdleTimer);
    document.getElementById("tableContainer").removeEventListener('scroll', resetIdleTimer);
  }

  function resetIdleTimer() {
    clearTimeout(time);
    time = setTimeout(action, maxIdleMillis)
  }
}

function setData() {
  if (columns.length > 0 && rows.length > 0) {
    for (var i=0; i<columns.length; i++){
      var column = data[columns[i].id];
      if (column){
        setColumnData(i,column);
      }
    }
  }
}

function setCell (cell, row, values) {
  if (cell.firstChild && cell.firstChild.nodeName == "INPUT") { // input cell - we are editing this
    var input = cell.firstChild;
    if (modifiedRows.has(input)){ // we already changed it - flag conflict
      input.classList.add("conflict");
    }
    input.classList.remove("reported");
    input.value = editValue(row,values[row.id]);

  } else { // just a display cell but flag values outside range
    cell.textContent = displayValue( row, rowList.rows, values);
  }
}

function setColumnData (i, columnData, filterRow) {
  var tbody = document.getElementById('table_body');
  var trDtgs = document.getElementById('column_dtgs');
  var i1 = i + 1;
  var values = columnData.rows;

  trDtgs.childNodes[i1].textContent = utils.timeString(columnData.date);

  for (var j=0; j<rows.length; j++){
    var row = rows[j];
    if (filterRow && !filterRow(row)) continue;

    var tr = tbody.childNodes[j];
    var cell = tr.childNodes[i1];

    setCell(cell, row, values);
  }
}

function formatArray (v) {
  var s = JSON.stringify(v);
  if (s.length > 15) return "[..]";
  return s;
}

function formatValue (row,cv) {
  if (cv == undefined) return "";
  var v = cv.value;

  if (row.type == "integer") {
    if (Number.isInteger(v)) return intFormatter.format(v);
    else return ratIntFormatter.format(v);
  }

  if (row.type == "real"){
    if (Number.isInteger(v)) return intRatFormatter.format(v); 
    else return ratFormatter.format(v);
  }

  if (row.type.endsWith("[]")) return formatArray(v);

  return v;
}

// used to display readOnly cells
function displayValue (row, rowList, rowValues) {
  return formatValue(row, rowValues[row.id]);
}

// used to initialize input elements
function editValue (row, cv) {
  if (cv == undefined) return "";
  return JSON.stringify(cv.value);
}

function columnIndex (columnName) {
  for (var i=0; i<columns.length; i++){
    if (columns[i].id == columnName) return i;
  }
  return -1;
}

function processCells (cellFunc){
  var tbody = document.getElementById("table_body");

  for (var i=0; i<rows.length; i++) {
    var row = rows[i];
    var tr = tbody.childNodes[i];
    for (var j=0; j<columns.length; j++){
      var column = columns[j];
      var cell = tr.childNodes[j+1];

      cellFunc( cell, column, row);
    }
  }
}

/**
 * this computes the rows from rowList that match the current rowFilter (glob pattern)
 */
function filterRows () {
  var pattern = document.getElementById("rowFilter").value;
  if (pattern) {
    var regex = glob.glob2regexp(pattern); //new RegExp(pattern)
    return rowList.rows.filter( f => regex.test(f.id) && !isHidden(f));
  } else {
    return rowList.rows.filter( f => !isHidden(f));
  }
}

/**
 * set display label and indentation level properties for each row object
 * This function could also add artificial header rows, i.e. could return a different
 * row array than what we pass in
 */
function displayRows (filteredRows) {
  var headerStack = []; // stack of header ids

  for (let r of filteredRows) {
    var rid = r.id;
    var pid = utils.parentOfPath(rid);

    while (headerStack.length>0 && utils.top(headerStack) != pid) headerStack.pop();
    if (headerStack.length == 0) {
      r.label = utils.isAbsolutePath(rid) ? rid.substring(1) : rid;
      r.level = 0;
    } else {
      r.label = utils.nameOfPath(rid);
      r.level = headerStack.length;
    }

    headerStack.push(rid);
  }

  return filteredRows;
}


function rowIdPrefix (row) {
  if (row.id.endsWith('/')) {
    return row.id;
  } else {
    return row.id + '/';
  }
}

function expandRow (i) {
  var row = rows[i];
  var idPrefix = rowIdPrefix(row);
  var k = i+1;

  if (row.isCollapsed) { // expand - add all children
    var allRows = rowList.rows;
    for ( var j = allRows.indexOf(row)+1; j < allRows.length; j++){
      var f = allRows[j];
      if (!isHidden(f) && f.id.startsWith(idPrefix)) {
        rows.splice(k,0,f);
        k += 1;
      } else break;
    }
    row.isCollapsed = false;

  } else { // collapse - remove all children
    while (k < rows.length && rows[k].id.startsWith(idPrefix)){
      rows.splice(k,1);
    }
    row.isCollapsed = true;
  }

  initTable();
  setData();
}

function filterColumns () {
  var pattern = document.getElementById("columnFilter").value;
  if (pattern) {
    var regex = glob.glob2regexp(pattern); //new RegExp(pattern);
    return columnList.columns.filter( p => regex.test(p.id))
  } else {
    return columnList.columns;
  }
}

//--- column/row highlighting

export function highlightColumn (i, isSelected) {
  var tbody = document.getElementById("table_body");
  var idCell = document.getElementById("column_names").childNodes[i];
  //var pathCell = document.getElementById("column_paths").childNodes[i];
  var dtgCell = document.getElementById("column_dtgs").childNodes[i];

  if (isSelected) {
    idCell.classList.add("selected");
    //pathCell.classList.add("selected");
    dtgCell.classList.add("selected");
    for (var row of tbody.childNodes) {
      row.childNodes[i].classList.add("selected");
    }
    showColumnInfo(columns[i-1]);
  } else {
    idCell.classList.remove("selected");
    //pathCell.classList.remove("selected");
    dtgCell.classList.remove("selected");
    for (var row of tbody.childNodes) {
      row.childNodes[i].classList.remove("selected");
    }
    utils.info(null);
  }
}

function showColumnInfo (column) {
  var txt = column.id + ": " + column.info;
  utils.info(txt);
}

export function highlightRow (i, isSelected) {
  var row = document.getElementById("table_body").childNodes[i];
  if (isSelected) {
    for (var cell of row.childNodes) {
      cell.classList.add( "selected");
    }
    showRowInfo(rows[i]);
  } else {
    for (var cell of row.childNodes) {
      cell.classList.remove("selected");
    }
    utils.info(null);
  }
}

function showRowInfo (row) {
  var txt = row.id + ": " + row.info;
  if (row.min || row.max){
    txt += ".         [";
    if (row.min) txt += row.min;
    txt += ' , '
    if (row.max) txt += row.max;
    txt += ']';
  }
  if (row.formula) {
    txt += ".         =";
    txt += row.formula;
  }
  utils.info(txt);
}

export function clickRow (i) {
  if (isHeader(rows[i])) {
    expandRow(i);
  }
}


//--- incoming messages

function handleWsOpen (evt) {
  var status = document.getElementById("status");
  status.classList.add("ok");
  status.value = "connected";
}

function handleWsClose (evt) {
  var status = document.getElementById("status");
  utils.swapClass(status,"ok","alert");
  status.value = "disconnected";

  checkForReconnect();
}

var maxReconnectAttempts = 15;
var reconnectAttempt = 1;

function checkForReconnect () {
  if (reconnectAttempt >= maxReconnectAttempts) {
    document.getElementById("status").value = "no server";
    return;
  } else {
    document.getElementById("status").value = "server check " + reconnectAttempt;
    reconnectAttempt += 1;
  }

  const req = new XMLHttpRequest();
  const url = utils.parentOfPath(document.URL);
  req.open("GET", url);
  req.send();

  //req.onerror = (e) => {
  //  console.log("server unresponsive: " + JSON.stringify(e));
  //}
  console.log("trying to reconnect.. " + reconnectAttempt);

  req.onreadystatechange = (e) => {
    if (req.status == 200) {
      console.log("reconnected - reloading document");
      location.reload();
    } else {
      setTimeout(checkForReconnect, 5000);
    }
  }
}

// the websocket message handler (msg is the payload data object of the websocket message)
function handleWsMessage(msg) {
  //console.log(JSON.stringify(msg));
  var msgType = Object.keys(msg)[0];  // first member name
  //console.log(JSON.stringify(msg));

  if (msgType == "columnDataChange") handleColumnDataChange(msg.columnDataChange);
  else if (msgType == "columnData") handleColumnData( msg.columnData);
  else if (msgType == "rowList") handleRowList( msg.rowList);
  else if (msgType == "columnList") handleColumnList( msg.columnList);
  else if (msgType == "userPermissions") handleUserPermissions( msg.userPermissions);
  else if (msgType == "siteId")  handleSiteId( msg.siteId);
  else if (msgType == "ping") handlePing( msg.ping);
  else utils.log(`ignoring unknown message type ${msgType}`);
};

// { columnDataChange: { columnId:"s", changeNodeId:"s", date:n, changedValues: { <rowId>: { value:X,date:n } ... }}}
function handleColumnDataChange (cdc) {
  //console.log(JSON.stringify(cdc));
  var i = columnIndex(cdc.columnId);
  if (i >= 0) {
    var cd = data[cdc.columnId];
    cd.date = new Date(cdc.date)
    var cvs = cdc.changedValues;
    var changedRowIds = Object.keys(cvs);

    changedRowIds.forEach( function (rowId,idx) {
      var cv = cvs[rowId];
      cd.rows[rowId] = cv;
    });

    setColumnData(i, cd, function(row) { return cvs.hasOwnProperty(row.id); });
  }
}


// {columnData:{id:"s",rev:n,date:n,rows:[<rowId>:n]}
function handleColumnData (columnData) {
  columnData.date = new Date(columnData.date) // convert epoch into Date object

  if (columnData.rows){
    if (data[columnData.id]) utils.log(`${columnData.id} data updated`);

    data[columnData.id] = columnData;
    if ((rows.length > 0) && (columns.length > 0)){
      var i = columnIndex(columnData.id);
      if (i>=0) {
        setColumnData(i, columnData);
      }
    }
  }
}

function handleSiteId (id) {
  siteId = id;
  utils.setAndFitToText( document.getElementById("siteId"), id, 7);
}

// {rowList:{rev:n,rows:[{id:"s",info:"s" <,header:"s">}..]}}
function handleRowList (newRowList) {
  rowList = newRowList;
  rows = displayRows(filterRows());

  utils.setAndFitToText( document.getElementById("rowListId"), rowList.id, 7);

  if (hasColumns())  {
    initTable();
    setData();
  }
}

// {columnList:{ rev:n, columns:[ {id:"s",info:"s"}.. ]}}
function handleColumnList (newColumnList) {
  columnList = newColumnList;
  columns = filterColumns();

  utils.setAndFitToText( document.getElementById("columnListId"), columnList.id, 7);

  if (hasRows())  {
    initTable();
    setData();
  }
}

// {uid,permissions{}}
function handleUserPermissions (usrPerm) {
  //console.log(JSON.stringify(usrPerm));
  var uid = usrPerm.uid; // TODO - should we check if that is the current user? Not critical since update has to be checked by server anyways
  if (usrPerm.permissions) {
    setRowsEditable(usrPerm.permissions);
    utils.log(`enter edit mode for user ${uid}`)
  } else {
    utils.log(`reject edit mode for user ${uid}`);
  }
}

function handlePing (ping) {
  var reply = `{"pong":{"date":${Date.now()},"ping":${JSON.stringify(ping)}}}`;
  ws.send(reply);
}

//--- outgoing messages

function sendRequestUserPermissions (uid,pw){
  if (ws) {
    if (utils.isEmpty(uid) || utils.isEmpty(pw)) {
      alert("please enter user id and password and try again");
    } else {
      ws.send(`{"requestUserPermissions":{"${uid}":[${utils.toUtf8Array(pw).join(',')}]}}`);
    }
  }
}

function sendUserChange (uid,pw,changeDate,column,changedRows) {
  if (ws) {
    var msg = `{"userChange":{"${uid}":[${utils.toUtf8Array(pw).join(',')}],"date":${changeDate},"columnListId":"${columnList.id}","rowListId":"${rowList.id}","nodeId":"${siteId}","${column.id}":{${changedRows}}}}`;
    ws.send(msg);
    utils.log(`sent data changes for ${column.id}`);
  }
}

//--- row display

export function setRowFocused (event) {
  var input = event.target;
  input.classList.add("focused");
}

export function setRowBlurred (event) {
  var input = event.target;
  input.classList.remove("focused");
}

export function setRowModified(event) {
  var input = event.target;

  if (!input.classList.contains("modified")){
    input.classList.add("modified");

    modifiedRows.add(input);
  }
}

function checkOutdatedColumns() {
  console.log("@@ check outdated")
}

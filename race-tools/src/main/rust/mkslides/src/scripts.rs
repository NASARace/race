
pub fn get_odin_slides_js() -> String {
    ODIN_SLIDES_JS.to_string()
}

const ODIN_SLIDES_JS: &'static str = r#"
var slides = document.getElementsByClassName("slide");
var curIdx = 0;

var isFullScreen = false;
var firstDigit = 0;

var timer = document.getElementById('timer');
var timerInterval = 5; // in sec
var hours = 0;
var minutes = 0;
var seconds = 0;
var t = 0;
var timed = false
var slideshow = false

var navView = undefined;
var navSelection = 0;

const webRunBasePort = 4000;

var slideCounter = document.getElementById('counter');

showCounter();
setRunHandlers();
setSrvText();
setSlideSelectors();
createNavView();

function createNavView() {
  let v = document.createElement("DIV");
  v.classList.add("nav_view");

  let list = document.createElement("OL");
  v.appendChild(list);

  let li = createNavItem(document.title);
  list.appendChild(li);

  for (let e of document.getElementsByTagName("H2")) {
    li = createNavItem( e.textContent);
    list.appendChild(li);
  }

  document.body.appendChild(v);
  navView = v;
}

function createNavItem (txt) {
    let li = document.createElement("LI");
    li.classList.add("nav_item");
    li.textContent = txt;
    li.addEventListener("click", gotoNavItem);
    return li;
}

function showNavView() {
  navView.classList.add("show");
}

function hideNavView() {
  navView.classList.remove("show");
}

function gotoNavItem (event) {
  let e = event.target.previousSibling;
  let idx = 0;
  while (e) {
    idx++;
    e = e.previousSibling;
  }
  slides[idx].scrollIntoView();
}

function setRunHandlers() {
  for (let elem of document.getElementsByClassName("run")) {
    elem.addEventListener( "click", function(e) {
      var match = elem.textContent.match(/^(\d):\s*(.+)\s*$/);
      var consoleNumber = parseInt(match[1]);
      var cmd = match[2];
      sendWebRunRequest(elem, consoleNumber, cmd);
      return false;
    }, false);
  }
}

function sendWebRunRequest (elem, consoleNumber, cmd) {
  var port = webRunBasePort + consoleNumber;
  var request = new XMLHttpRequest();

  request.onload = function () {
    if (request.responseText == "done") {
      elem.classList.remove("running");
    }
  }

  console.log(`http://localhost:${port}/run {${cmd}}`);

  request.open( "POST", `http://localhost:${port}/run`, true);
  request.setRequestHeader( "Content-Type", "text/plain;charset=UTF-8");
  request.send(cmd);

  elem.classList.add("running");
}

function setSrvText() {
  for (let elem of document.getElementsByClassName("srv")) {
    if (elem.nodeName == "A") {
      var link = elem.getAttribute("href");
      if (link) {
        elem.textContent = link;
      }
    }
  }
}

function setSlideSelectors() {
  var navList = document.getElementsByClassName("nav-list")[0];
  if (navList) {
    var children = navList.children;
    for (var i=0; i < children.length; i++) {
      var clickTo = function(idx) {
        return function curried () {
          curIdx = idx;
          showCounter();
          console.log("set cur = " + curIdx);
        }
      }

      var childElem = children[i];
      var anchorElem = childElem.firstChild;
      if (anchorElem && anchorElem.tagName == "A"){
        anchorElem.addEventListener( "click",  clickTo(i));
      }
    }
  }
}

function toggleFullScreen() {
  if (!isFullScreen){
    var elem = document.documentElement;
    isFullScreen = true;
    if (elem.mozRequestFullScreen) {
      elem.mozRequestFullScreen();
    } else if (elem.webkitRequestFullscreen) {
      elem.webkitRequestFullscreen();
    } else if (elem.requestFullscreen) {
      elem.requestFullscreen();
    }
  } else {
    isFullScreen = false;
    if(document.exitFullscreen) {
      document.exitFullscreen();
    } else if(document.mozCancelFullScreen) {
      document.mozCancelFullScreen();
    } else if(document.webkitExitFullscreen) {
      document.webkitExitFullscreen();
    }
  }
}

function incTime() {
  if (timed){
    t += timerInterval;
    seconds = t % 60;
    minutes = ~~(t/60) % 60;
    hours = ~~(t/3600)
    timer.textContent = (hours ? (hours > 9 ? hours : "0" + hours) : "00") + ":" +
                        (minutes ? (minutes > 9 ? minutes : "0" + minutes) : "00") + ":" +
                        (seconds > 9 ? seconds : "0" + seconds);

    setTimeout(incTime,timerInterval*1000);
  }
}

function toggleTimer() {
  if (timed){
    timed = false;
    timer.style.color = '#0000FF';
  } else {
    timed = true;
    timer.style.color = '#FF0000';
    setTimeout(incTime,timerInterval*1000);
  }
}

function scheduleNextSlide() {
  if (slideshow){
    var idx = curIdx + 1;
    if (idx >= slides.length) idx = 0;
    scrollToSlide(idx);
    setTimeout(scheduleNextSlide, timerInterval*1000*3);
  }
}

function toggleSlideshow() {
  if (slideshow) {
    slideshow = false;
    toggleTimer();
  } else {
    slideshow = true;
    if (!timed) toggleTimer();
    setTimeout(scheduleNextSlide, timerInterval*1000*3);
  }
}

function showCounter() {
  slideCounter.textContent = (curIdx+1) + " / " + slides.length;
}

function scrollToSlide (idx) {
  if (idx < 0) idx = 0;
  if (idx >= slides.length) idx = slides.length-1;

  if (idx == 0) {
    window.scrollTo(0,0);
  } else {
    slides[idx].scrollIntoView({behavior: "smooth", block: "start", inline: "nearest"});
  }

  curIdx = idx;
  showCounter();
}

var navMode = false;
var gotoMode = false;
var gotoIdx = 0;

document.addEventListener("keydown", e => {
  var kc = e.keyCode;

  if (kc == 13 || kc == 32 || kc == 34){  // Enter: next slide, Shift+Enter: prev slide
    if (navMode && kc == 13) {
        navMode = false;
        hideNavView();
    } else {
        if (gotoMode) {
            gotoMode = false;
            scrollToSlide( gotoIdx-1);
        } else {
            scrollToSlide( e.shiftKey ? curIdx-1 : curIdx+1);
        }
    }
    e.preventDefault();
  }
  else if (kc == 40 || kc == 34) { // down, pgDown
    scrollToSlide( curIdx + 1);
    e.preventDefault();
  }
  else if (kc == 33 || kc == 38) { // up, pgUp
    scrollToSlide( curIdx - 1);
    e.preventDefault();
  }
  else if (kc == 36) { // home
    scrollToSlide(0);
    e.preventDefault();
  }
  else if (kc == 36) { // end
    scrollToSlide(slides.length);
    e.preventDefault();
  }
  else if (kc == 27) {
    gotoMode = false;
    if (navMode) {
      hideNavView();
      navMode = false;
    }
  }
  else if (kc == 70) { // 'f' toggle full screen
    toggleFullScreen();
  }
  else if (kc == 84){  // 't' toggles timer
    toggleTimer();
  }
  else if (kc == 83) { // 's' toggles show
    toggleSlideshow();
  }
  else if (kc == 71) { // 'g' goto slide
    gotoMode = true;
    gotoIdx = 0;
  }
  else if (kc == 78) { // 'n' toggle nav mode
    gotoMode = false;

    navMode = !navMode;
    if (navMode) {
        showNavView();
    } else {
        hideNavView();
    }
  }
  else if (kc >=48 && kc <= 57){ // 0..9 set gotoIdx
    var n = kc - 48;
    if (gotoMode) {
        gotoIdx *= 10;
        gotoIdx += n;
    }
  }
});
"#;


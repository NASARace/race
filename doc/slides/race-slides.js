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

const webRunBasePort = 4000;

var slideCounter = document.getElementById('counter');
showCounter();
setRunHandlers();
setSrvText();
setSlideSelectors();


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
  if (idx >=0 && idx < slides.length){
    if (idx == 0) {
      window.scrollTo(0,0);
    } else {
      slides[idx].scrollIntoView({behavior: "smooth", block: "start", inline: "nearest"});
    }

    curIdx = idx;
    showCounter();
  }
}

document.addEventListener("keydown", e => {
  var kc = e.keyCode;

  if (kc == 13 || kc == 32 || kc == 34){  // Enter: next slide, Shift+Enter: prev slide
    scrollToSlide( e.shiftKey ? curIdx-1 : curIdx+1);
    e.preventDefault();
  }
  else if (kc == 33) {
    scrollToSlide( curIdx - 1);
    e.preventDefault();
  }
  else if (kc == 34) {
    scrollToSlide( curIdx + 1);
    e.preventDefault();
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
  else if (kc >=48 && kc <= 57){ // jump to slide 0..9
    var idx = kc - 48;
    if (e.ctrlKey){
      if (idx == 0) {
        scrollToSlide(1);
        return;
      }
      firstDigit = idx;
    } else {
      if (idx > 0) idx -= 1;
      if (firstDigit) {
        idx += firstDigit * 10;
        firstDigit = undefined;
      }

      scrollToSlide(idx);
    }
  }
});

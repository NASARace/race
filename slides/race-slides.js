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

var slideCounter = document.getElementById('counter');
showCounter();

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

function showCounter() {
  slideCounter.textContent = curIdx + " / " + (slides.length-1);
}

function scrollToSlide (idx) {
  if (idx >=0 && idx < slides.length){
    if (idx == 0) {
      window.scrollTo(0,0);
    } else {
      slides[idx].scrollIntoView({block: "start", inline: "nearest", behavior: "smooth"});
    }

    curIdx = idx;
    showCounter();
  }
}

document.onkeypress = function (e) {
  var kc = e.which
  if (kc == 13){  // Enter: next slide, Shift+Enter: prev slide
    scrollToSlide( e.shiftKey ? curIdx-1 : curIdx+1);
  }
  else if (kc == 102) { // 'f' toggle full screen
    toggleFullScreen();
  }
  else if (kc == 116){  // 't' toggles timer
    toggleTimer();
  }
  else if (kc >=48 && kc <= 57){ // jump to slide 0..9
    var idx = kc - 48;
    if (e.ctrlKey){
      firstDigit = idx;
    } else {
      if (firstDigit > 0) {
        idx += firstDigit * 10;
        firstDigit = 0;
      }
      scrollToSlide(idx);
    }
  }
};

var slides = document.getElementsByClassName("slide");
var isFullScreen = false;
var firstDigit = 0;

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

function smoothScrollTo (endY){
  var dur = 900; // ms
  var dt = 15;
  var nSteps = dur / dt;
  var dd = Math.PI / nSteps;

  var startY = window.pageYOffset;
  var dh = startY - endY;

  var count = 1;

  function step() {
    if (count < nSteps){
      var y = endY + dh * ( Math.cos(count*dd)+1)/2.0;
      window.scrollTo(0,y);
      count += 1;
      requestAnimationFrame(animationStep);
    } else {
      window.scrollTo(0,endY); // avoid rounding error in last step
    }
  }
  function animationStep(){
    setTimeout(step,dt);
  }

  requestAnimationFrame(step);
}

function scrollToSlide (idx){
  if (idx >=0 && idx < slides.length){
    var endY = window.pageYOffset + slides[idx].getBoundingClientRect().top;
    //window.scrollTo(0, endY;
    smoothScrollTo(endY);
  }
}

function targetSlideIndex (delta){
  var minDist = Number.MAX_SAFE_INTEGER;
  var lastDist = Number.MAX_SAFE_INTEGER;
  var closestIndex = 0;
  for(var i = 0; i < slides.length; i++){
    var dist = Math.abs(slides[i].getBoundingClientRect().top);
    if (dist < minDist){
      minDist = dist;
      closestIndex = i;
    }
    if (dist > lastDist) break; // it's monotone
    lastDist = dist;
  }
  return Math.min( Math.max(0,closestIndex + delta),slides.length-1);
}

document.onkeypress = function (e) {
  if (e.which == 13){  // Enter: next slide, Shift+Enter: prev slide
    scrollToSlide( targetSlideIndex(e.shiftKey ? -1 : 1));
  }
  else if (e.which == 102) { // 'f' toggle full screen
    toggleFullScreen();
  }
  else if (e.which >=48 && e.which <= 57){ // jump to slide 0..9
    var idx = e.which - 48;
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

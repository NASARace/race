/*
 * Copyright (c) 2023, United States Government, as represented by the
 * Administrator of the National Aeronautics and Space Administration.
 * All rights reserved.
 *
 * The RACE - Runtime for Airspace Concept Evaluation platform is licensed
 * under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
use crate::ARGS;

pub fn get_odin_style_css() -> String {
    ODIN_STYLE_CSS.to_string()
}

pub fn style_vars () -> String {
    let mut style_vars = String::with_capacity(256);

    style_vars.push_str(":root {");
    style_vars.push_str(&format!("  --font-family: {};\n", &ARGS.basic_font_family));
    style_vars.push_str(&format!("  --font-size: {};\n", &ARGS.basic_font_size));

    style_vars.push_str(&format!("  --h1-factor: {}%;\n", &ARGS.h1_factor));
    style_vars.push_str(&format!("  --h2-factor: {}%;\n", &ARGS.h2_factor));
    style_vars.push_str(&format!("  --item-factor: {}%;\n", &ARGS.li_factor));

    style_vars.push_str(&format!("  --header-margin: {};\n", &ARGS.header_margin));
    style_vars.push_str(&format!("  --list-margin: {};\n", &ARGS.list_margin));
    style_vars.push_str(&format!("  --item-margin: {};\n", &ARGS.item_margin));

    style_vars.push_str( "}");

    style_vars
}

const ODIN_STYLE_CSS: &'static str = r#"
body {
    margin: 0 10px 0 10px;
    font-size: var(--font-size);
    font-family: var(--font-family);
}

/*---------------------------- print support -----------------------------*/
@media print {
  div.slide {
      display: block;
      height: 100vh;
      page-break-before: always;
      padding: 0 10px 0 10px;
  }
}

@media screen {
  div.slide {
      display: block;
      height: 100vh;
      padding: 0 10px 0 10px;
      overflow: hidden;
  }
}

.title h1 {
    margin-top: 10vh;
    margin-bottom: 1em;
    font-size: var(--h1-factor);
}

.title {
    text-align: center;
}

.slide h2,h3 {
    text-align: center;
    font-size: var(--h2-factor);
    margin: 0;
    padding: var(--header-margin);
    border-bottom: 2px solid LightGray;
}

p.author, address {
    display: block;
    font-size: 80%;
    font-family: Courier;
    color: Gray;
    margin-top: 5em;
    text-align: center;
}

table.keys {
    margin-top: 5rem;
    margin-left: auto;
    margin-right: auto;
    font-size: 80%;
    font-weight: lighter;
    color: Darkgreen;
}

.align-right {
    text-align: right;
}

.align-left {
    text-align: left;
}

.fixed {
    font-family: Courier;
}

.align-center {
    text-align: center;
}

p.comment {
  display: block;
  font-size: 80%;
  font-style: italic;
  text-align: center;
}

p.box {
    display: block;
    border: 2px solid orange;
    padding: 0.5em;
    margin: 0.5em;
    background: LightYellow;
}

p.standout {
    display: block;
    padding: 0.5em;
    margin: 0.5em;
    text-align: Center;
    font-weight: bolder;
}

div.spacer {
    width: 100%;
}

.slide ul {
    list-style-type: disc;
}

.slide ul ul {
    list-style-type: circle;
    margin: var(--list-margin);
}

.slide li {
    margin: var(--item-margin);
}

ul.nav-list {
    list-style-type: decimal;
    font-size: 60%;
    text-decoration: none;
}

img.logo {
    width: 5.0vw;
    height: 5.0vw;
    float: left;
}

pre {
    font-size: 80%;
    color: DarkBlue;
    background:#f4f4f4;
}

code {
    font-size: 80%;
    color: DarkBlue;
}

.stopwatch {
    position: fixed;
    right: 0;
    top: 0;
    font-size: 50%;
    color: Blue;
}

.slidecounter {
    position: fixed;
    left: 0;
    top: 0;
    font-size: 50%;
    color: Blue;
}

div.run {
    display: block;
    cursor: pointer;
    font-size: 60%;
    font-family: monospace;
    color: gray;
}

div.running {
    color: Green;
}

a.srv {
    font-size: 60%;
    color: gray;
}

div.nav_view {
    display: none;
    position: fixed;
    bottom: 15px;
    right: 15px;
    border: 1px solid rgb(110,110,110);
    box-shadow: 5px 5px 8px rgb(136, 136, 136);
    background: rgb(42, 42, 64);
    padding: 5px;
}

div.nav_view.show {
   display: block;
   z-index: 50;
}

div.nav_view ol {
   margin: 0;
}

li.nav_item {
    font-size: 12pt;
    margin: 3pt;
    color: rgb(200, 200, 200);
}

li.nav_item:hover {
    background: rgb(64, 64, 200);
}


table {
    border-collapse: collapse;
    overflow: hidden;
}

td {
    padding: 0.3rem 1rem 0.3rem 1rem;
    border: 1px solid;
    position: relative;
}

th {
    background-color: #e0e0e0; 
    padding: 0.3rem 1rem 0.3rem 1rem;
    border-top: 1px solid;
    border-bottom: 2px solid;
    border-left: 1px solid;
    border-right: 1px solid;
    position: relative;
}

td:hover::before { 
    background-color: #ffa;
    content: '';  
    height: 100%;
    left: -5000px;
    position: absolute;  
    top: 0;
    width: 10000px;   
    z-index: -2;        
}

th:hover::after { 
    background-color: #ffa;
    content: '';  
    height: 10000px;    
    left: 0;
    position: absolute;  
    top: -5000px;
    width: 100%;
    z-index: -1;        
}

td:hover {
    background-color: #00ffff;
}


/*---------------------------- content image classes -----------------------------*/

/* left and right have to be wrapped into a div */

img.left {
    /*float: left;*/
    display: inline-block;
    vertical-align: middle;
    /*padding: 1rem 2rem 0 1rem; */
    margin: 15px 30px 15px 15px;
}

img.right {
    /*float: right;*/
    display: inline-block;
    vertical-align: middle;
    /* padding: 1rem 1rem 0 2rem; */
    margin: 15px 15px 15px 30px;
}

img.rightFloat {
    float: right;
    display: inline-block;
}

img.center {
    display: block;
    /*padding: 1rem 1rem 0 1rem; */
    margin: 15px auto 15px auto;
}

img.alignTop {
    vertical-align: text-top;
}

img.alignBottom {
    vertical-align: text-bottom;
}

img.alignBase {
    vertical-align: baseline;
}

img.scale10 {
    height: 10vh;
}
img.scale15 {
    height: 15vh;
}
img.scale20 {
    height: 20vh;
}
img.scale25 {
    height: 25vh;
}
img.scale30 {
    height: 30vh;
}
img.scale35 {
    height: 35vh;
}
img.scale40 {
    height: 40vh;
}
img.scale45 {
    height: 45vh;
}
img.scale50 {
    height: 50vh;
}
img.scale55 {
    height: 55vh;
}
img.scale60 {
    height: 60vh;
}
img.scale65 {
    height: 65vh;
}
img.scale70 {
    height: 70vh;
}
img.scale75 {
    height: 75vh;
}
img.scale80 {
    height: 80vh;
}
img.scale85 {
    height: 85vh;
}
img.scale90 {
    height: 90vh;
}
img.scale95 {
    height: 95vh;
}
img.scale100 {
    height: 100vh;
}

/* most browsers have problems with image scale when printint */
@media print {
  img.scale15 {
      height: 10vh;
  }
  img.scale20 {
    height: 15vh;
  }
  img.scale25 {
      height: 20vh;
  }
  img.scale35 {
      height: 30vh;
  }
  img.scale40 {
      height: 30vh;
  }
  img.scale45 {
      height: 37vh;
  }
  img.scale50 {
      height: 40vh;
  }
  img.scale55 {
      height: 45vh;
  }
  img.scale60 {
      height: 50vh;
  }
  img.scale65 {
      height: 55vh;
  }
  img.scale70 {
      height: 60vh;
  }
  img.scale75 {
      height: 65vh;
  }
  img.scale80 {
      height: 70vh;
  }
  img.scale85 {
      height: 75vh;
  }
  img.scale90 {
      height: 80vh;
  }
}

img.back {
   z-index: -1;
}

img.up50 {
  position: relative;
  top: -50pt;
}
img.up75 {
  position: relative;
  top: -75pt;
}
img.up100 {
  position: relative;
  top: -100pt;
}
img.up125 {
  position: relative;
  top: -125pt;
}
img.up150 {
  position: relative;
  top: -150pt;
}
"#;
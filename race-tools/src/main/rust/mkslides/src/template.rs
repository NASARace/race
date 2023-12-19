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

pub fn get_html_template () -> String {
    ODIN_HTML_TEMPLATE.to_string()
}

const ODIN_HTML_TEMPLATE: &'static str = r#"
<!DOCTYPE html>
<html lang="en">
    <head>
        <meta charset="UTF-8"/>
        <title>{title}</title>

        <style>
{style_vars}
        </style>

        <style>
{style}
        </style>
    </head>

    <body>
        <div class="slidecounter" id="counter">0 / 0</div>
        <div class="stopwatch" id="timer">00:00</div>

        <div class="slide title">
{slides}
        </div>

    </body>

    <script>
{script}
    </script>
</html>
"#;
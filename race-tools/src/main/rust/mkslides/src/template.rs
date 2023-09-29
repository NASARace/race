
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
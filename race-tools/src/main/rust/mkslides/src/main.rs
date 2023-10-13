#![allow(unused)]

mod scripts;
mod template;
mod styles;

#[macro_use]
extern crate lazy_static;

use std::fs::File;
use std::io::Write;
use odin_common::fs;

use structopt::StructOpt;
use anyhow::{anyhow, Result};
use markdown::{to_html_with_options, ParseOptions, CompileOptions, Options};

#[derive(StructOpt)]
pub struct CliOpts {

    /// slide separator line
    #[structopt(long,default_value="---")]
    slide_separator: String,

    /// basic font family
    #[structopt(long,default_value="Arial, sans-serif")]
    basic_font_family: String,

    /// basic CSS font size
    #[structopt(long,default_value="3.5vh")]
    basic_font_size: String,

    /// title font size factor
    #[structopt(long,default_value="150")]
    h1_factor: u32,

    /// slide header font size factor
    #[structopt(long,default_value="130")]
    h2_factor: u32,

    /// slide list item font size factor
    #[structopt(long,default_value="110")]
    li_factor: u32,

    /// header margins
    #[structopt(long,default_value="0.2em 0 0.2em 0")]
    header_margin: String,

    /// list margins
    #[structopt(long,default_value="0.5em 0 0 0")]
    list_margin: String,

    /// item margins
    #[structopt(long,default_value="0 0 0.3em 0")]
    item_margin: String,

    /// HTML template
    #[structopt(short,long,default_value="odin_slides.html")]
    template_path: String,

    /// path for CSS
    #[structopt(short,long,default_value="odin_slides.css")]
    style_path: String,

    /// path for HTML output (default is 'index.html')
    #[structopt(short,long,default_value="index.html")]
    output_path: String,

    /// the markdown file path for the slides
    md_path: String
}

lazy_static! {
    #[derive(Debug)]
    pub static ref ARGS: CliOpts = CliOpts::from_args();
}

fn main() -> Result<()> {
    let mut md_file = fs::existing_non_empty_file_from_path(&ARGS.md_path)?;
    let md_raw_content = fs::file_contents_as_string(&mut md_file)?;

    let sep_line = format!("\n{}\n", ARGS.slide_separator);
    let md_content = md_raw_content.replace(&sep_line, "</div>\n<div class=\"slide\">\n");

    let slides_content = to_html_with_options(md_content.as_str(), &Options {
        compile: CompileOptions {
            allow_dangerous_html: true,
            allow_dangerous_protocol: true,
            ..CompileOptions::default()
        },
        parse: ParseOptions::gfm()
    }).map_err(|msg| anyhow!("{}",msg))?;

    let title = get_title(&md_raw_content);
    let html_template = template::get_html_template();
    let style_vars = styles::style_vars();
    let style_content = styles::get_odin_style_css();
    let script_content = scripts::get_odin_slides_js();

    let html_content = html_template
        .replace("{title}", &title)
        .replace("{style_vars}", &style_vars)
        .replace("{style}", &style_content)
        .replace("{slides}", &slides_content)
        .replace("{script}", &script_content);

    let mut output = File::create(&ARGS.output_path)?;
    output.write_all(html_content.as_bytes())?;

    println!("saved HTML for slide set '{}' to file '{}'.", title, &ARGS.output_path);
    Ok(())
}

fn get_title (slides_content: &str) -> String {
    let re = regex::Regex::new(r"\n# (.+)\n").unwrap();

    if let Some((_,[title])) = re.captures(slides_content).map(|caps| caps.extract()) {
        title.to_string()
    } else {
        ARGS.md_path.clone()
    }
}

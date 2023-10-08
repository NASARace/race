use std::path::PathBuf;
use cc::Build;

fn main() {
    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-changed=Cargo.lock");
    println!("cargo:rerun-if-changed=src/warpshim.c");

    // NOTE - depending on C compiler the include path for GDAL headers must be set through environment vars
    // (CPATH=<include-dirs> for gcc C/C++)

    let mut build = cc::Build::new();
    build.file("src/warpshim.c");
    build.flag("-O3");

    if check_gdal_header(&mut build) {
        build.compile("warpshim")
    } else {
        println!("cargo:warning=no CPATH environment variable set - don't know where to find gdal headers. abort");
        panic!("no C_FLAGS environment variable set - don't know where to find gdal headers. abort");
    }
}

fn check_gdal_header (build: &mut Build) -> bool {
    let hdr = "gdal.h";

    if cfg!(unix) {
        let std_paths = "/usr/include:/usr/include/gdal:/usr/local/include:/usr/local/include/gdal";
        if let Some(_) = find_in_paths(std_paths, ":", hdr) {
            // CC should look at these automatically
            return true
        }

        if cfg!(target_os = "macos") {
            // check local homebrew and global MacPorts - we have to tell the compiler about these
            let hb_path = format!("{}/homebrew/include:/opt/local/include", std::env::var("HOME").unwrap());
            if let Some(p) = find_in_paths(hb_path.as_str(), ":", hdr) {
                println!("found gdal.h in {}", p);
                build.flag(format!("-I{}",p).as_str());
                return true;
            }

        } else { // other unix'ish OS
            // /opt/include ? should be prefixed with a vendor name (we don't know)
        }

        // check CPATH environment var
        if let Ok(ref cpath) = std::env::var("CPATH") {
            if let Some(_p) = find_in_paths(cpath, ":", hdr) {
                // nothing to add as CC should check CPATH itself
                return true;
            }
        }

    } else if cfg!(windows) {
        // don't know yet. Probably vcpkg
    }

    false
}

fn find_in_paths (paths: &str, sep: &str, fname: &str) -> Option<String> {
    let mut pb = PathBuf::new();
    for p in paths.split(sep) {
        pb.clear();
        pb.push(p);
        pb.push(fname);
        if pb.as_path().is_file() {
            return Some(p.to_string())
        }
    }
    None
}
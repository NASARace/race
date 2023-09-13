
fn main() {
    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-changed=Cargo.lock");
    println!("cargo:rerun-if-changed=src/warpshim.c");

    // NOTE - depending on C compiler the include path for GDAL headers must be set through environment vars
    // (CPATH=<include-dirs> for gcc C/C++)

    cc::Build::new()
        .file("src/warpshim.c")
        .flag(env!("C_FLAGS"))
        //.include(env!("GDAL_INCLUDE_DIR"))
        //.cpp(true)
        // .flag(env!("CXX_FLAGS"))
        //.flag("-std=c++17")
        .compile("warpshim")
}

fn main() {
    cc::Build::new()
        .cpp(true)
        .file("src/warpshim.cpp")
        //.include(env!("GDAL_INCLUDE_DIR"))
        //.flag(env!("CXX_FLAGS"))
        .flag("-std=c++17")
        .compile("warpshim")
}
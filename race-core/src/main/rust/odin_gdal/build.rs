fn main() {
    cc::Build::new()
        .cpp(true)
        .file("src/warpshim.cpp")
        .compile("warpshim")
}
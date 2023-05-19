#include "arg_opts.h"
#include <iostream>

using std::cout, std::cerr, std::endl, std::string;

bool arg_opts::parse (int argc, char **argv) {
    if (argc <2 || strcmp(argv[1], "-h")== 0) {
        cout << usageMsg << endl;
        return false;
    }

    int idx = 1;
    if (strcmp(argv[idx],"-z") == 0){
        idx++;
        compress = true;
    }

    if (argc < idx +2) {
        cerr << "not enough arguments\n";
        return false;
    }

    inputFile = argv[idx++];
    outputFile = argv[idx];

    return true;
}

string arg_opts::expanded_outputFile () {
    string fname = outputFile;

    if (fname.ends_with(".gz")) {
        fname.insert(0, "/vsigzip/");
    } else if (compress) {
        fname.insert(0, "/vsigzip/");
        fname += ".gz";
    }

    return fname;
}
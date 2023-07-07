#ifndef ARGS_H
#define ARGS_H

#include <iostream>

struct arg_opts {
    const char* usageMsg = "";
    bool compress = false;
    const char* inputFile = nullptr;
    const char* outputFile = nullptr;

    arg_opts (const char* msg) {
        usageMsg = msg;
    }

    bool parse (int argc, char **argv);
    std::string expanded_outputFile();
};

#endif
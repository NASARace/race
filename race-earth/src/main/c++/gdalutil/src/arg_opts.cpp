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
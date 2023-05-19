#!/usr/bin/env python3

#
# Copyright (c) 2023, United States Government, as represented by the
# Administrator of the National Aeronautics and Space Administration.
# All rights reserved.
#
# The RACE - Runtime for Airspace Concept Evaluation platform is licensed
# under the Apache License, Version 2.0 (the "License"); you may not use
# this file except in compliance with the License. You may obtain a copy
# of the License at http://www.apache.org/licenses/LICENSE-2.0.
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

###
# macOS specific Python script to recursively collect *.dylib dependencies of a given executable into a 
# specified install dir and update respective LC_LOAD_DYLIB entries.
#
# This is a utility script to create (mostly) self-contained distributions of external executables used by RACE.
# The script should cover both aarch and amd64.
# 
# NOTE - Do not attempt to resolve dependencies from system paths (e.g. /usr/lib/libc++.1.dylib) as those probably
# depend on specific OS versions
#

import subprocess
import sys
import os
import shutil

if len(sys.argv) < 4:
    print("usage: install-bin-macos.py <executable> <tgt-lib-dir> <src-lib-dir>...")
    print("example: install-bin-macos.py ./WindNinja_cli lib ~/homebrew")
    print("         (recursively installs ~/homebrew/**/*.dylib files required by WindNinja_cli executable into lib/ and updates LC_LOAD_DYLIB entries)")
    sys.exit()

examine = [sys.argv[1]]
installDir = sys.argv[2]
resolvePrefixes = sys.argv[3:]

unresolved = []
nProcessed = 0

def processBin (filename):
    global nProcessed 

    print("-- processing", filename)
    bn = os.path.basename(filename)
    nProcessed += 1

    o = subprocess.Popen(['otool', '-L', filename], stdout=subprocess.PIPE)
    for line in o.stdout:
        line = line.decode('UTF-8')
        if line[0] == '\t':
            pathname = line[1:].split(' ',1)[0]
            basename = os.path.basename(pathname)
            if not basename == bn:
                if True in (pathname.startswith(pre) for pre in resolvePrefixes):
                    resolve(filename, pathname, pathname, basename)

                elif pathname.startswith('@rpath'):
                    pn = findRpathLib(basename)
                    if pn:
                         resolve(filename, pathname, pn, basename)
                    else:
                        unresolved.append( (filename,pathname))
                    

def resolve (filename, refPathname, realPathname, basename):
    dst = f"{installDir}/{basename}"
    changeDylibPath(filename,refPathname, basename)

    if not os.path.isfile(dst):
        print(realPathname)
        shutil.copy(realPathname,dst)
        examine.append(dst)


def changeDylibPath (filename, refPathname, basename):
    abspath = os.path.abspath(filename)
    tgtPathname = f'@executable_path/{installDir}/{basename}'
    subprocess.run(['install_name_tool', '-change', refPathname, tgtPathname, '-id', abspath, filename], capture_output=True)
    subprocess.run(['codesign', '--force', '-s', '-', filename], capture_output=True)


# this is a rather naive approach - we would have to dig the RPATH out of the user
def findRpathLib(fn):
    for dir in resolvePrefixes:
        for root,_,files in os.walk(dir):
            for name in files:
                if name == fn:
                    return os.path.join(root,name)

    None

if not os.path.exists(installDir):
    os.makedirs(installDir)

print("installing dependencies to", installDir)

while examine:
    processBin(examine.pop(0))

print("\nresolved files:", nProcessed)

if unresolved:
    print("\nunresolved dependencies:")
    for e in unresolved:
        print("  ", e[0],":",e[1])


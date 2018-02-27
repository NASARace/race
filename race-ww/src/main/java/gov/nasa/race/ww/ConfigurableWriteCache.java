/*
 * Copyright (c) 2016, United States Government, as represented by the
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

package gov.nasa.race.ww;

import gov.nasa.worldwind.cache.BasicDataFileStore;
import java.io.File;
import java.net.URL;

import org.w3c.dom.Node;

/**
 * our own configurable DataFileStore
 *
 * since we can only configure the class name, we have to cache the cacheRoot in a static
 */
public class ConfigurableWriteCache extends BasicDataFileStore {

    private static File cacheRoot = new File(System.getProperty("user.home") + "/.WorldWindData");

    public static File ensureDir (File dir) {
        if (dir.isFile()){
            throw new IllegalArgumentException("cache root not a directory: " + dir);
        }

        if (!dir.isDirectory()){
            dir.mkdirs();
        }
        return dir;
    }

    public static void setRoot (File rootDir) {
        cacheRoot = rootDir;
    }

    public ConfigurableWriteCache() {
        buildWritePaths(null);
        buildReadPaths(null);
    }

    @Override
    public File getWriteLocation() {
        return cacheRoot;
    }

    @Override
    protected void buildWritePaths(Node arg0) {
        ensureDir(cacheRoot);

        writeLocation = new StoreLocation(cacheRoot);
        readLocations.add(0, writeLocation);
    }

    // this is called after buildWritePaths, i.e. writeLocation is already set
    @Override
    protected void buildReadPaths(Node arg0) {
        readLocations.add(0, writeLocation);
    }


    /**
    @Override
    protected void updateEntry(String address, URL localFileUrl, long expiration) {
        super.updateEntry(address,localFileUrl,0);
    }
    **/
}
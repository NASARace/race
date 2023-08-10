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

use std::io;
use std::fs;
use std::path::{Path};

/// check if dir pathname exists and is writable, try to create dir otherwise
pub fn ensure_writable_dir (dir: &str) -> io::Result<()> {
    let path = Path::new(dir);

    if path.is_dir() {
        let md = fs::metadata(&path)?;
        if md.permissions().readonly() {
            Err(io::Error::new(io::ErrorKind::PermissionDenied, format!("output_dir {:?} not writable", &path)))
        } else {
            Ok(())
        }

    } else {
        fs::create_dir(&path)
    }
}
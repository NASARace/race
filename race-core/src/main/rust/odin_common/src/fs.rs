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
use std::io::{Read, Write};
use io::ErrorKind::*;
use std::fs::File;
use std::path::{Path,PathBuf};

use crate::macros::{io_error};

/// check if dir pathname exists and is writable, try to create dir otherwise
pub fn ensure_writable_dir (dir: &str) -> io::Result<()> {
    let path = Path::new(dir);

    if path.is_dir() {
        let md = fs::metadata(&path)?;
        if md.permissions().readonly() {
            Err(io_error!(PermissionDenied, "output_dir {:?} not writable", &path))
        } else {
            Ok(())
        }

    } else {
        fs::create_dir(&path)
    }
}

pub fn filepath (dir: &str, filename: &str) -> io::Result<PathBuf> {
    let mut pb = PathBuf::new();
    pb.push(dir);
    pb.push(filename);
    Ok(pb)
}

pub fn readable_file (dir: &str, filename: &str) -> io::Result<File> {
    let p = filepath(dir,filename)?;
    if p.is_file() {
        File::open(p)
    } else {
        Err(io_error!(Other, "not a regular file {:?}", p))
    }
}

pub fn writable_empty_file (dir: &str, filename: &str) -> io::Result<File> {
    File::create(filepath(dir,filename)?)
}

pub fn file_contents_as_string (file: &mut fs::File) -> io::Result<String> {
    let len = file.metadata()?.len();
    let mut contents = String::with_capacity(len as usize);
    file.read_to_string(&mut contents)?;
    Ok(contents)
}

pub fn filepath_contents_as_string (dir: &str, filename: &str) -> io::Result<String> {
    let mut file = readable_file(dir,filename)?;
    file_contents_as_string( &mut file)
}

pub fn file_length(file: &File) -> Result<u64,io::Error> {
    file.metadata().and_then( |md| {
        if md.is_file() {
            Ok(md.len())
        } else {
            Err(io_error!(NotFound, "file {:?}", file))
        }
    })
}

pub fn existing_non_empty_file (dir: &str, filename: &str) -> io::Result<fs::File> {
    let mut pb = PathBuf::new();
    pb.push(dir);
    pb.push(filename);

    match File::open(pb) {
        Ok(file) => {
            let md = file.metadata()?;
            if md.is_file() {
                if md.len() > 0 {
                    Ok(file)
                } else {
                    Err(io_error!(Other, "file empty: {:?}", file))
                }
            } else {
                Err(io_error!(Other, "not a file: {:?}", file))
            }
        },
        Err(e) => Err(e)
    }
}

pub fn create_file_with_backup (dir: &str, filename: &str, ext: &str) -> io::Result<File> {
    let mut pb = PathBuf::new();
    pb.push(dir);
    pb.push(filename);
    let p = pb.as_path();

    if p.is_file() && p.metadata()?.len() > 0 {
        let mut pb_bak = pb.clone();
        pb_bak.push(ext);
        let p_bak = pb_bak.as_path();

        if p_bak.is_file() { fs::remove_file(p_bak)?; }
        fs::rename(p, p_bak)?;
    }

    File::create(p)
}

pub fn set_filepath_contents (dir: &str, filename: &str, new_contents: &[u8]) -> io::Result<()> {
    let mut file = writable_empty_file(dir,filename)?;
    set_file_contents(&mut file, new_contents)
}

pub fn set_file_contents(file: &mut File, new_contents: &[u8]) -> io::Result<()> {
    file.write_all(new_contents)
}

pub fn set_filepath_contents_with_backup (dir: &str, filename: &str, backup_ext: &str, new_contents: &[u8]) -> io::Result<()> {
    let mut file = create_file_with_backup(dir,filename,backup_ext)?;
    set_file_contents(&mut file, new_contents)
}
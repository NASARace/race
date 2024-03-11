/*
 * Copyright (c) 2024, United States Government, as represented by the
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

use tokio;
use rpassword;
use secstr::SecStr;
use std::{ops::Fn, sync::{Arc,Mutex}, time::Duration};
use crate::{errors::config_error, prelude::{ConfigResult, OdinConfigError}};

type Result<T> = ConfigResult<T>;

/// an interactive password store to be used within tokio applications.
/// The store will reset itself automatically after a configured duration.
/// This is mainly used to avoid repetitive entry of the same password/passphrase
/// in a sequence of operations (e.g. during init)
/// The pw bytes on the heap are overwritten when the PwCache is reset.
/// 
/// NOTE - the public interface relies on that provided closures do not leak the
/// pw data. In case of the with_string_pw() it is also up to the caller closure
/// to ensure there is no readable (copied) String data left on the heap. Some clients
/// don't do this, which makes the program vulnerable against core dump post mortem analysis
#[derive(Clone)]
pub struct PwCache {
    prompt: String,
    cache: Arc<Mutex<Option<SecStr>>>, // needs to be Arc/Mutex because of async reset
    lifespan: Duration,
}

impl PwCache {
    pub fn new(prompt: impl ToString, lifespan: Duration)->Self {
        PwCache { 
            prompt: prompt.to_string(), 
            cache: Arc::new(Mutex::new(None)), 
            lifespan 
        }
    }

    /// execute the provided func with a borrowed SecStr password. If there is none set yet,
    /// obtain it from the user and spawn a task that will erase it after the lifespan duration.
    /// Make sure when this task erases it there are no cleartext bytes left on the heap
    pub fn with_pw<F,T> (&self, func: F)->Result<T> 
        where F: Fn(&SecStr)->Result<T> 
    {
        if let Ok(mut locked_cache) = self.cache.lock() {
            if locked_cache.is_none() {
                let pw = rpassword::prompt_password(&self.prompt)?;
                let sec_pw = SecStr::new(pw.into_bytes());
                locked_cache.replace(sec_pw);

                let mut c = self.cache.clone();
                tokio::spawn ( async move {
                    tokio::time::sleep(Duration::from_secs(5)).await;
                    match c.lock() { // make sure we erase no matter if there was a LockResult error
                        Ok(mut locked_c) => {
                            let mut _drop_me = locked_c.take();
                            //_drop_me.zero_out()
                            // automatically zeroed out when _drop_me is dropped here
                        }
                        Err(poison_error) => {
                            if let Some(mut _drop_me) = poison_error.into_inner().take() {
                                //_drop_me.zero_out()
                                // automatically zeroed out when _drop_me is dropped here
                            }
                        }
                    }
                });
            }

            match *locked_cache {
                Some(ref pw) => func(pw), // NOTE - this means `func`` is now responsible for not leaking pw bytes
                None => Err(Self::err("no pw set"))
            }
        } else {
            Err(Self::err("cannot obtain PwCache lock"))
        }
    }

    pub fn with_u8_pw<F,T> (&self, func: F)->Result<T> 
        where F: Fn(&[u8])->Result<T>
    {
        self.with_pw(|sec_pw| {
            let mut bs =  sec_pw.unsecure();
            func(bs)
        })
    } 

    /// this is more safe than with_(ref_)string_pw() since it avoids copying, but that might be moot if
    /// func() just calls to_string() on its argument
    pub fn with_str_pw<F,T> (&self, func: F)->Result<T> 
        where F: Fn(&str)->Result<T>
    {
        self.with_pw(|sec_pw| {
            let mut bs =  sec_pw.unsecure();
            if let Ok(s) = std::str::from_utf8(bs) {
                func(s)
            } else {
                Err(Self::err("pw not valid utf-8"))
            }
        })
    } 

    /// this is slightly more safe than with_string_pw() as we at least make sure the copied String
    /// is erased before we return the result. It still does not guarantee the pw does not leak
    /// from the called func
    pub fn with_ref_string_pw<F,T> (&self, func: F)->Result<T> 
        where F: Fn(&String)->Result<T>
    {
        self.with_pw(|sec_pw| {
            let mut s =  sec_pw.to_string();
            let result = func(&s);
            Self::clear_string(s);
            result
        })
    } 

    /// the least secure variant, in case we have to provide a String for callers.
    /// NOTE - this might also leak the clear text pw by leaving clear text copies on the heap, which
    /// only depends on if the provided `func` erases the passed in String data or not 
    pub fn with_string_pw<F,T> (&self, func: F)->Result<T> 
        where F: Fn(String)->Result<T>
    {
        self.with_pw(|sec_pw| {
            let mut s =  sec_pw.to_string();
            func(s)
        })
    }

    /// same as [`with_pw`] except of that we don't try to obtain the pw from the user if it is not
    /// already set
    pub fn try_with_pw<F,T> (&self, func: F)->Result<T> 
        where F: Fn(&SecStr)->Result<T> 
    {
        if let Ok(mut locked_cache) = self.cache.lock() {
            match *locked_cache {
                Some(ref pw) => func(pw), // NOTE - this means `func`` is now responsible for not leaking pw bytes
                None => Err(Self::err("no pw set"))
            }
        } else {
            Err(Self::err("cannot obtain PwCache lock"))
        }
    }
 
    /// same as [`with_str_pw`] except of that we don't try to obtain the pw from the user if it is not
    /// already set
    pub fn try_with_str_pw<F,T> (&self, func: F)->Result<T> 
        where F: Fn(&str)->Result<T> 
    {
        self.try_with_pw(|sec_pw| {
            let mut bs =  sec_pw.unsecure();
            if let Ok(s) = std::str::from_utf8(bs) {
                func(s)
            } else {
                Err(Self::err("pw not valid utf-8"))
            }
        })
    }

    /// same as [`with_ref_string_pw`] except of that we don't try to obtain the pw from the user if it is not
    /// already set. Less secure than [`try_with_pw`] or [`try_with_str_pw`]
    pub fn try_with_ref_string_pw<F,T> (&self, func: F)->Result<T> 
        where F: Fn(&String)->Result<T>
    {
        self.try_with_pw(|sec_pw| {
            let mut s =  sec_pw.to_string();
            let result = func(&s);
            Self::clear_string(s);
            result
        })
    } 

    /// same as [`with_string_pw`] except of that we don't try to obtain the pw from the user if it is not
    /// already set. Less secure than the other try_with variants as it might leave cleartext data on
    /// the heap
    pub fn try_with_string_pw<F,T> (&self, func: F)->Result<T> 
        where F: Fn(String)->Result<T>
    {
        self.try_with_pw(|sec_pw| {
            let mut s =  sec_pw.to_string();
            func(s)
        })
    }

    pub fn clear_string (mut s: String) {
        let len = s.len();
        s.clear(); // this does not change capacity
        for i in 0..len { s.push('\x00') }
    }

    #[inline]
    fn err(msg: impl ToString)->OdinConfigError {
        config_error(msg.to_string())
    }
}

//! the sim_clock module is basically a singleton implementation that can be set either to an instance
//! that corresponds to wall time (i.e. is just a wrapper around `chrono::DateTime<Utc>`) or to a 
//! simulation time that can be set, reset an suspended/resumed.
//! 
//! The main reason for this module is to enforce a system-wide single initialization and to provide
//! an API that can be used for both simulation and live operation. Note that clients should always
//! test `sim_clock::is_settable()` before invoking operations that might result in errors

#![allow(unused)]

use chrono::{DateTime,Utc,Local};
use std::{time::{Duration, Instant}, sync::{Mutex,OnceLock,PoisonError,MutexGuard}};
use thiserror::Error;

#[derive(Error,Debug)]
pub enum OdinClockError {
    #[error("clock init error: {0}")]
    ClockInitError(String),

    #[error("clock not initialized")]
    ClockNotInitialized,

    #[error("clock not resettable")]
    ClockNotResettable,

    #[error("clock not suspendable")]
    ClockNotSuspendable,

    #[error("illegal clock op {0}")]
    IllegalClockOp(String),

    #[error("poisoned lock error {0}")]
    PoisonedLockError(#[from] PoisonError<MutexGuard<'static,SettableSimClock>>),

    #[error("poisoned lock error {0}")]
    PoisonedMutError(#[from] PoisonError<&'static mut SettableSimClock>),
}

pub struct SettableSimClock {
    start_dt: DateTime<Utc>, // the set sim time start
    wall_start: Instant, // the measured SystemTime that corresponds to it

    timescale: u32,
    is_resettable: bool,
    is_suspendable: bool,

    suspend_dt: Option<DateTime<Utc>>
}

impl SettableSimClock {
    pub fn new (start_dt: DateTime<Utc>, timescale: u32, is_resettable: bool, is_suspendable: bool)->Self {
        SettableSimClock { start_dt, wall_start: Instant::now(), timescale, is_resettable, is_suspendable, suspend_dt: None }
    }

    pub fn reset (&mut self, start_dt: DateTime<Utc>, timescale: u32)->Result<(),OdinClockError> {
        if self.is_resettable {
            self.start_dt = start_dt;
            self.wall_start = Instant::now();
            Ok(())
        } else { Err( OdinClockError::ClockNotResettable) }
    }

    #[inline]
    pub fn now (&self)->DateTime<Utc> {
        self.start_dt + (Instant::now() - self.wall_start) * self.timescale
    }

    pub fn now_local (&self)->DateTime<Local> {
        self.now().with_timezone(&Local)
    }

    pub fn epoch_millis (&self)->i64 {
       self.now().timestamp_millis()
    }

    /// we allow but ignore multiple consecutive suspend() requests
    pub fn suspend (&mut self)->Result<(),OdinClockError>  {
        if self.is_suspendable {
            if self.suspend_dt.is_none() {
                self.suspend_dt = Some(self.now());
            }
            Ok(())
        } else { Err( OdinClockError::ClockNotSuspendable) }
    }

    pub fn resume (&mut self)->Result<(),OdinClockError> {
        if self.is_suspendable {
            match self.suspend_dt {
                Some(dt) => {
                    self.start_dt = dt;
                    self.wall_start = Instant::now();
                    Ok(())
                }
                None => Err( OdinClockError::IllegalClockOp("clock not suspended".to_string()))
            }
        } else { Err( OdinClockError::ClockNotSuspendable) }
    }

    pub fn is_suspended (&self)->bool {
        self.suspend_dt.is_some()
    }
}

enum SimClock {
    Settable(Mutex<SettableSimClock>),
    Wall
}

/// our global SimClock instance (don't make this public)
static SIM_CLOCK: OnceLock<SimClock> = OnceLock::new();

pub fn initialize (start_dt: DateTime<Utc>, timescale: u32, is_resettable: bool, is_suspendable: bool)->Result<(),OdinClockError> {
    SIM_CLOCK.set( SimClock::Settable(Mutex::new(SettableSimClock::new(start_dt, timescale, is_resettable, is_suspendable))))
        .map_err(|e| OdinClockError::ClockInitError("sim clock already initialized".to_string()))
}

 /// this is infallible but we want to keep the return type the same 
pub fn initialize_wall ()->Result<(),OdinClockError> {
    SIM_CLOCK.set( SimClock::Wall);
    Ok(())
}

pub fn is_settable ()->Result<bool,OdinClockError> {
    match SIM_CLOCK.get() {
        Some(sim_clock) => {
            match sim_clock {
                SimClock::Settable(_) => Ok(true),
                SimClock::Wall => Ok(false)
            }
        }
        None => Err( OdinClockError::ClockNotInitialized)
    }
}

pub fn now()->Result<DateTime<Utc>,OdinClockError> {
    match SIM_CLOCK.get() {
        Some(sim_clock) => {
            match sim_clock {
                SimClock::Settable(sim_clock) => Ok(sim_clock.lock()?.now()),
                SimClock::Wall => Ok(Utc::now())
            }
        }
        None => Err( OdinClockError::ClockNotInitialized)
    }
}

pub fn now_local()->Result<DateTime<Local>,OdinClockError> {
    match SIM_CLOCK.get() {
        Some(sim_clock) => 
            match sim_clock {
                SimClock::Settable(sim_clock) => Ok(sim_clock.lock()?.now_local()),
                SimClock::Wall => Ok(Local::now())
            }
        None => Err( OdinClockError::ClockNotInitialized)
    }
}

pub fn epoch_millis()->Result<i64,OdinClockError> {
    match SIM_CLOCK.get() {
        Some(sim_clock) => 
            match sim_clock {
                SimClock::Settable(sim_clock) => Ok(sim_clock.lock()?.epoch_millis()),
                SimClock::Wall => Ok(Utc::now().timestamp_millis())
            }        
        None => Err( OdinClockError::ClockNotInitialized)
    }
}

pub fn reset (start_dt: DateTime<Utc>, timescale: u32)->Result<(),OdinClockError> {
    match SIM_CLOCK.get() {
        Some(sim_clock) => {
            match sim_clock {
                SimClock::Settable(sim_clock) => {
                    let mut sim_clock = sim_clock.lock()?;
                    sim_clock.reset( start_dt, timescale)
                }
                SimClock::Wall => Err( OdinClockError::IllegalClockOp("wall clock cannot be reset".to_string()))
            }   
        }
        None => Err( OdinClockError::ClockNotInitialized)
    }
}

pub fn suspend ()->Result<(),OdinClockError> {
    match SIM_CLOCK.get() {
        Some(sim_clock) => {
            match sim_clock {
                SimClock::Settable(sim_clock) => {
                    let mut sim_clock = sim_clock.lock()?;
                    sim_clock.suspend()
                }
                SimClock::Wall => Err( OdinClockError::IllegalClockOp("wall clock cannot be suspended".to_string()))
            }   
        }
        None => Err( OdinClockError::ClockNotInitialized)
    }
}

pub fn resume ()->Result<(),OdinClockError> {
    match SIM_CLOCK.get() {
        Some(sim_clock) => {
            match sim_clock {
                SimClock::Settable(sim_clock) => {
                    let mut sim_clock = sim_clock.lock()?;
                    sim_clock.resume()
                }
                SimClock::Wall => Err( OdinClockError::IllegalClockOp("wall clock cannot be resumed".to_string()))
            }   
        }
        None => Err( OdinClockError::ClockNotInitialized)
    }
}

pub fn is_suspended ()->Result<bool,OdinClockError> {
    match SIM_CLOCK.get() {
        Some(sim_clock) => 
            match sim_clock {
                SimClock::Settable(sim_clock) => Ok(sim_clock.lock()?.is_suspended()),
                SimClock::Wall => Ok(false) // wall clock can't be suspended
            }        
        None => Err( OdinClockError::ClockNotInitialized)
    }
}
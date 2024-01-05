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
use thiserror::Error;
use std::time::Duration;

pub type Result<T> = std::result::Result<T, OdinActorError>;

#[derive(Error,Debug)]
pub enum OdinActorError {

    #[error("oneshot consumed")]
    OneshotConsumed,

    #[error("receiver closed")]
    ReceiverClosed,

    #[error("receiver queue full")]
    ReceiverFull,

    #[error("senders dropped")]
    SendersDropped,

    #[error("timeout error: {0:?}")]
    TimeoutError(Duration),

    #[error("{op} failed for {failed} out of {all} actors")]
    AllOpFailed { op: String, all: usize, failed: usize },

    #[error("IO error {0}")]
    IOError( #[from] std::io::Error),

    #[error("config parse error {0}")]
    ConfigParseError(String),

    #[error("config write error {0}")]
    ConfigWriteError(String),

    #[error("poisoned lock error {0}")]
    PoisonedLockError(String),

    #[error("failed to join task")]
    JoinError,

    // a generic error
    #[error("operation failed {0}")]
    OpFailed(String)

    //... and more to come
}

pub fn all_op_result (op: &'static str, total: usize, failed: usize)->Result<()> {
    if failed == 0 { Ok(()) } else { Err(all_op_failed( op, total, failed)) }
}

pub fn all_op_failed <T: ToString> (op: T, all: usize, failed: usize)->OdinActorError {
    OdinActorError::AllOpFailed { op: op.to_string(), all, failed }
}

pub fn poisoned_lock <T: ToString> (op: T)->OdinActorError {
    OdinActorError::PoisonedLockError(op.to_string())
}

pub fn op_failed (msg: impl ToString)->OdinActorError {
    OdinActorError::OpFailed(msg.to_string())
}

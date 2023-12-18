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

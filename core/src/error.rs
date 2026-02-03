//! Error types for Atmosphere

use thiserror::Error;

/// Main error type for Atmosphere operations
#[derive(Error, Debug)]
pub enum AtmosphereError {
    #[error("Node not initialized")]
    NotInitialized,

    #[error("Network error: {0}")]
    Network(String),

    #[error("Serialization error: {0}")]
    Serialization(#[from] serde_json::Error),

    #[error("Capability not found: {0}")]
    CapabilityNotFound(String),

    #[error("No capable peer found for intent: {0}")]
    NoCapablePeer(String),

    #[error("Signature error: {0}")]
    Signature(String),

    #[error("Connection failed: {0}")]
    ConnectionFailed(String),

    #[error("Timeout: {0}")]
    Timeout(String),

    #[error("Invalid configuration: {0}")]
    InvalidConfig(String),

    #[error("Internal error: {0}")]
    Internal(String),
}

/// Result type alias for Atmosphere operations
pub type Result<T> = std::result::Result<T, AtmosphereError>;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_error_display() {
        let err = AtmosphereError::CapabilityNotFound("camera".to_string());
        assert_eq!(err.to_string(), "Capability not found: camera");
    }

    #[test]
    fn test_error_from_serde() {
        let json_err = serde_json::from_str::<String>("not valid json").unwrap_err();
        let err: AtmosphereError = json_err.into();
        assert!(matches!(err, AtmosphereError::Serialization(_)));
    }
}

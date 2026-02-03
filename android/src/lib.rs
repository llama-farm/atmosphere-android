//! Atmosphere Android Bindings
//!
//! JNI bindings for the Atmosphere core library.
//! This is a placeholder - full implementation coming later.

pub use atmosphere_core::*;

/// Placeholder for JNI initialization
/// Will be implemented with proper JNI bindings later
pub fn init() {
    // tracing::info!("Atmosphere Android bindings initialized (placeholder)");
    println!("Atmosphere Android bindings initialized (placeholder)");
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_core_reexport() {
        // Verify core types are accessible
        let _id = NodeId::new();
        let _cap = Capability::new("test", "Test");
    }
}

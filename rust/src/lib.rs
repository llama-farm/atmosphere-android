//! Atmosphere Core - Native inference engine for Android
//!
//! This library provides JNI bindings for the Atmosphere Android app,
//! exposing LLM inference capabilities to Kotlin/Java code.

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use log::{info, Level};
use std::sync::atomic::{AtomicBool, Ordering};

// Global state for model loading
static MODEL_LOADED: AtomicBool = AtomicBool::new(false);

/// Initialize Android logger
#[allow(dead_code)]
fn init_logger() {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag("AtmosphereCore"),
    );
}

/// JNI: Run inference on the given prompt
///
/// # Arguments
/// * `env` - JNI environment
/// * `_class` - Class reference (unused)
/// * `prompt` - Input prompt string
///
/// # Returns
/// Generated response string
#[no_mangle]
pub extern "C" fn Java_com_llamafarm_atmosphere_viewmodel_ChatViewModel_nativeInference(
    mut env: JNIEnv,
    _class: JClass,
    prompt: JString,
) -> jstring {
    // Get the input string from Java
    let input: String = match env.get_string(&prompt) {
        Ok(s) => s.into(),
        Err(e) => {
            let error_msg = format!("Failed to get prompt string: {}", e);
            return env
                .new_string(error_msg)
                .expect("Couldn't create error string")
                .into_raw();
        }
    };

    info!("Received inference request: {} chars", input.len());

    // TODO: Implement actual LLM inference here
    // For now, return a placeholder response
    let response = if MODEL_LOADED.load(Ordering::SeqCst) {
        // Placeholder for actual inference
        format!("Model response to: {}", input)
    } else {
        format!(
            "Model not loaded. Echo: {}",
            input.chars().take(100).collect::<String>()
        )
    };

    // Return the response
    match env.new_string(response) {
        Ok(output) => output.into_raw(),
        Err(e) => {
            let error_msg = format!("Failed to create response string: {}", e);
            env.new_string(error_msg)
                .expect("Couldn't create error string")
                .into_raw()
        }
    }
}

/// JNI: Load a model from the given path
///
/// # Arguments
/// * `env` - JNI environment
/// * `_class` - Class reference (unused)
/// * `model_path` - Path to the model file
///
/// # Returns
/// `true` if model loaded successfully, `false` otherwise
#[no_mangle]
pub extern "C" fn Java_com_llamafarm_atmosphere_viewmodel_ChatViewModel_00024Companion_nativeLoadModel(
    mut env: JNIEnv,
    _class: JClass,
    model_path: JString,
) -> jboolean {
    let path: String = match env.get_string(&model_path) {
        Ok(s) => s.into(),
        Err(e) => {
            info!("Failed to get model path: {}", e);
            return JNI_FALSE;
        }
    };

    info!("Loading model from: {}", path);

    // TODO: Implement actual model loading
    // For now, simulate success
    MODEL_LOADED.store(true, Ordering::SeqCst);

    info!("Model loaded successfully");
    JNI_TRUE
}

/// JNI: Unload the currently loaded model
///
/// # Arguments
/// * `_env` - JNI environment (unused)
/// * `_class` - Class reference (unused)
#[no_mangle]
pub extern "C" fn Java_com_llamafarm_atmosphere_viewmodel_ChatViewModel_00024Companion_nativeUnloadModel(
    _env: JNIEnv,
    _class: JClass,
) {
    info!("Unloading model");

    // TODO: Implement actual model unloading
    MODEL_LOADED.store(false, Ordering::SeqCst);

    info!("Model unloaded");
}

/// JNI: Check if a model is currently loaded
///
/// # Arguments
/// * `_env` - JNI environment (unused)
/// * `_class` - Class reference (unused)
///
/// # Returns
/// `true` if a model is loaded, `false` otherwise
#[no_mangle]
pub extern "C" fn Java_com_llamafarm_atmosphere_viewmodel_ChatViewModel_00024Companion_nativeIsModelLoaded(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    if MODEL_LOADED.load(Ordering::SeqCst) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_model_state() {
        assert!(!MODEL_LOADED.load(Ordering::SeqCst));
        MODEL_LOADED.store(true, Ordering::SeqCst);
        assert!(MODEL_LOADED.load(Ordering::SeqCst));
        MODEL_LOADED.store(false, Ordering::SeqCst);
        assert!(!MODEL_LOADED.load(Ordering::SeqCst));
    }
}

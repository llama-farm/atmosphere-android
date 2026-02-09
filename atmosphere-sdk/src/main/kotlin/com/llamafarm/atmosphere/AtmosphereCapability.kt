package com.llamafarm.atmosphere

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Represents a capability available in the Atmosphere mesh.
 */
@Parcelize
data class AtmosphereCapability(
    /** Unique identifier for this capability */
    val id: String,
    
    /** Human-readable name */
    val name: String,
    
    /** Capability type: "llm", "camera", "voice", "sensor", etc. */
    val type: String,
    
    /** Node ID that hosts this capability */
    val nodeId: String,
    
    /** Current cost score (0.0 = free, 1.0 = expensive) */
    val cost: Float,
    
    /** Whether this capability is currently available */
    val available: Boolean,
    
    /** Additional metadata as JSON string */
    val metadata: String
) : Parcelable

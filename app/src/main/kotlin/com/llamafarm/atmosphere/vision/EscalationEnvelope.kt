package com.llamafarm.atmosphere.vision

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Everything needed for the next model AND for training feedback.
 * 
 * This is the core data structure that flows through the entire cascade system.
 * When a model is uncertain, it doesn't just send an image -- it sends everything
 * the next model needs to make a better decision, and everything the training
 * pipeline needs to learn from the outcome.
 * 
 * Wire-compatible with LlamaFarm's Python implementation.
 */
data class EscalationEnvelope(
    // The image
    val imageBytes: ByteArray,           // Original full frame
    val imageHash: String,               // For dedup across mesh
    val sourceId: String,                // "drone-cam-1", "phone-main", etc.
    val timestamp: Long = System.currentTimeMillis(),
    
    // What each model saw (grows as it cascades)
    val opinions: List<ModelOpinion>,    // Ordered: first model first
    
    // Bounding boxes from detection (ALWAYS present if anything was found)
    val detections: List<DetectionWithMask>,
    
    // Routing metadata
    val originNode: String,              // Atmosphere node ID or "local"
    val hops: Int = 0,                   // How many models have seen this
    val maxHops: Int = 3,                // Circuit breaker
    val urgency: String = "normal"       // "normal", "important", "critical"
) {
    companion object {
        /**
         * Compute hash for image deduplication.
         */
        fun hashImage(imageBytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(imageBytes)
            return hash.joinToString("") { "%02x".format(it) }.take(16)
        }
        
        /**
         * Create initial envelope from first detection.
         */
        fun create(
            imageBytes: ByteArray,
            sourceId: String,
            originNode: String,
            firstOpinion: ModelOpinion,
            detections: List<DetectionWithMask>
        ): EscalationEnvelope {
            return EscalationEnvelope(
                imageBytes = imageBytes,
                imageHash = hashImage(imageBytes),
                sourceId = sourceId,
                timestamp = System.currentTimeMillis(),
                opinions = listOf(firstOpinion),
                detections = detections,
                originNode = originNode,
                hops = 0,
                maxHops = 3,
                urgency = "normal"
            )
        }
        
        /**
         * Deserialize from JSON (from mesh or LlamaFarm).
         */
        fun fromJson(json: JSONObject): EscalationEnvelope {
            val opinionsArray = json.getJSONArray("opinions")
            val opinions = mutableListOf<ModelOpinion>()
            for (i in 0 until opinionsArray.length()) {
                opinions.add(ModelOpinion.fromJson(opinionsArray.getJSONObject(i)))
            }
            
            val detectionsArray = json.getJSONArray("detections")
            val detections = mutableListOf<DetectionWithMask>()
            for (i in 0 until detectionsArray.length()) {
                detections.add(DetectionWithMask.fromJson(detectionsArray.getJSONObject(i)))
            }
            
            // Image bytes may be base64 encoded
            val imageBase64 = json.optString("image_base64", "")
            val imageBytes = if (imageBase64.isNotEmpty()) {
                android.util.Base64.decode(imageBase64, android.util.Base64.NO_WRAP)
            } else {
                ByteArray(0)
            }
            
            return EscalationEnvelope(
                imageBytes = imageBytes,
                imageHash = json.getString("image_hash"),
                sourceId = json.getString("source_id"),
                timestamp = json.getLong("timestamp"),
                opinions = opinions,
                detections = detections,
                originNode = json.getString("origin_node"),
                hops = json.getInt("hops"),
                maxHops = json.optInt("max_hops", 3),
                urgency = json.optString("urgency", "normal")
            )
        }
    }
    
    /**
     * Serialize to JSON for mesh transmission.
     */
    fun toJson(includeImage: Boolean = false): JSONObject {
        val json = JSONObject()
        
        if (includeImage) {
            json.put("image_base64", android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP))
        }
        json.put("image_hash", imageHash)
        json.put("source_id", sourceId)
        json.put("timestamp", timestamp)
        
        val opinionsArray = JSONArray()
        opinions.forEach { opinionsArray.put(it.toJson()) }
        json.put("opinions", opinionsArray)
        
        val detectionsArray = JSONArray()
        detections.forEach { detectionsArray.put(it.toJson()) }
        json.put("detections", detectionsArray)
        
        json.put("origin_node", originNode)
        json.put("hops", hops)
        json.put("max_hops", maxHops)
        json.put("urgency", urgency)
        
        return json
    }
    
    /**
     * Add another model's opinion to the cascade.
     */
    fun withOpinion(opinion: ModelOpinion, updatedDetections: List<DetectionWithMask>? = null): EscalationEnvelope {
        return copy(
            opinions = opinions + opinion,
            detections = updatedDetections ?: detections,
            hops = hops + 1
        )
    }
    
    /**
     * Check if cascade should continue.
     */
    fun shouldEscalate(): Boolean {
        if (hops >= maxHops) return false
        
        // Get latest opinion
        val latest = opinions.lastOrNull() ?: return false
        
        // High confidence = done
        if (latest.confidence >= 0.7f) return false
        
        // Low/mid confidence = escalate
        return true
    }
    
    /**
     * Get the best opinion so far.
     */
    fun getBestOpinion(): ModelOpinion? {
        return opinions.maxByOrNull { it.confidence }
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as EscalationEnvelope
        
        if (!imageBytes.contentEquals(other.imageBytes)) return false
        if (imageHash != other.imageHash) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        var result = imageBytes.contentHashCode()
        result = 31 * result + imageHash.hashCode()
        return result
    }
}

/**
 * What one model thought about this image.
 */
data class ModelOpinion(
    val modelId: String,                 // "mobilenet_v3_small", "yolov8n", "remote:gpu-server/yolov8x"
    val nodeId: String,                  // Where this model ran
    val className: String,               // What it thinks this is
    val confidence: Float,               // How sure it is (0.0-1.0)
    val bbox: BoundingBox,               // Where the object is
    val maskPolygon: List<Point>? = null,  // Segmentation if available
    val inferenceTimeMs: Float,          // How long it took
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("model_id", modelId)
        json.put("node_id", nodeId)
        json.put("class_name", className)
        json.put("confidence", confidence)
        json.put("bbox", bbox.toJson())
        
        maskPolygon?.let {
            val maskArray = JSONArray()
            it.forEach { point ->
                maskArray.put(JSONArray().apply {
                    put(point.x)
                    put(point.y)
                })
            }
            json.put("mask_polygon", maskArray)
        }
        
        json.put("inference_time_ms", inferenceTimeMs)
        json.put("timestamp", timestamp)
        
        return json
    }
    
    companion object {
        fun fromJson(json: JSONObject): ModelOpinion {
            val maskArray = json.optJSONArray("mask_polygon")
            val maskPolygon = if (maskArray != null) {
                val points = mutableListOf<Point>()
                for (i in 0 until maskArray.length()) {
                    val pointArray = maskArray.getJSONArray(i)
                    points.add(Point(
                        x = pointArray.getDouble(0).toFloat(),
                        y = pointArray.getDouble(1).toFloat()
                    ))
                }
                points
            } else null
            
            return ModelOpinion(
                modelId = json.getString("model_id"),
                nodeId = json.getString("node_id"),
                className = json.getString("class_name"),
                confidence = json.getDouble("confidence").toFloat(),
                bbox = BoundingBox.fromJson(json.getJSONObject("bbox")),
                maskPolygon = maskPolygon,
                inferenceTimeMs = json.getDouble("inference_time_ms").toFloat(),
                timestamp = json.getLong("timestamp")
            )
        }
    }
}

/**
 * A detection with its segmentation mask attached.
 * This is what flows to the next model -- bbox + visual context.
 */
data class DetectionWithMask(
    val bbox: BoundingBox,
    val cropBytes: ByteArray? = null,    // Cropped region from the image
    val maskPolygon: List<Point>? = null,
    val maskRle: String? = null,         // Run-length encoded for storage
    val className: String,               // Best guess so far
    val confidence: Float                // Best confidence so far
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("bbox", bbox.toJson())
        
        cropBytes?.let {
            json.put("crop_base64", android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP))
        }
        
        maskPolygon?.let {
            val maskArray = JSONArray()
            it.forEach { point ->
                maskArray.put(JSONArray().apply {
                    put(point.x)
                    put(point.y)
                })
            }
            json.put("mask_polygon", maskArray)
        }
        
        maskRle?.let { json.put("mask_rle", it) }
        json.put("class_name", className)
        json.put("confidence", confidence)
        
        return json
    }
    
    companion object {
        fun fromJson(json: JSONObject): DetectionWithMask {
            val maskArray = json.optJSONArray("mask_polygon")
            val maskPolygon = if (maskArray != null) {
                val points = mutableListOf<Point>()
                for (i in 0 until maskArray.length()) {
                    val pointArray = maskArray.getJSONArray(i)
                    points.add(Point(
                        x = pointArray.getDouble(0).toFloat(),
                        y = pointArray.getDouble(1).toFloat()
                    ))
                }
                points
            } else null
            
            val cropBase64 = json.optString("crop_base64", "")
            val cropBytes = if (cropBase64.isNotEmpty()) {
                android.util.Base64.decode(cropBase64, android.util.Base64.NO_WRAP)
            } else null
            
            return DetectionWithMask(
                bbox = BoundingBox.fromJson(json.getJSONObject("bbox")),
                cropBytes = cropBytes,
                maskPolygon = maskPolygon,
                maskRle = json.optString("mask_rle", null),
                className = json.getString("class_name"),
                confidence = json.getDouble("confidence").toFloat()
            )
        }
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as DetectionWithMask
        
        if (cropBytes != null) {
            if (other.cropBytes == null) return false
            if (!cropBytes.contentEquals(other.cropBytes)) return false
        } else if (other.cropBytes != null) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        return cropBytes?.contentHashCode() ?: 0
    }
}

/**
 * Bounding box in normalized coordinates (0.0 - 1.0).
 */
data class BoundingBox(
    val x1: Float,  // Left
    val y1: Float,  // Top
    val x2: Float,  // Right
    val y2: Float   // Bottom
) {
    /**
     * Convert to pixel coordinates.
     */
    fun toPixels(imageWidth: Int, imageHeight: Int): BoundingBox {
        return BoundingBox(
            x1 = x1 * imageWidth,
            y1 = y1 * imageHeight,
            x2 = x2 * imageWidth,
            y2 = y2 * imageHeight
        )
    }
    
    /**
     * Get width and height.
     */
    fun width(): Float = x2 - x1
    fun height(): Float = y2 - y1
    
    /**
     * Get center point.
     */
    fun center(): Point = Point((x1 + x2) / 2f, (y1 + y2) / 2f)
    
    /**
     * Compute IoU (Intersection over Union) with another box.
     */
    fun iou(other: BoundingBox): Float {
        val intersectX1 = maxOf(x1, other.x1)
        val intersectY1 = maxOf(y1, other.y1)
        val intersectX2 = minOf(x2, other.x2)
        val intersectY2 = minOf(y2, other.y2)
        
        if (intersectX2 < intersectX1 || intersectY2 < intersectY1) {
            return 0f
        }
        
        val intersectArea = (intersectX2 - intersectX1) * (intersectY2 - intersectY1)
        val box1Area = width() * height()
        val box2Area = other.width() * other.height()
        val unionArea = box1Area + box2Area - intersectArea
        
        return intersectArea / unionArea
    }
    
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("x1", x1)
        json.put("y1", y1)
        json.put("x2", x2)
        json.put("y2", y2)
        return json
    }
    
    companion object {
        fun fromJson(json: JSONObject): BoundingBox {
            return BoundingBox(
                x1 = json.getDouble("x1").toFloat(),
                y1 = json.getDouble("y1").toFloat(),
                x2 = json.getDouble("x2").toFloat(),
                y2 = json.getDouble("y2").toFloat()
            )
        }
        
        /**
         * Create from YOLO format (cx, cy, w, h in normalized coords).
         */
        fun fromYolo(cx: Float, cy: Float, w: Float, h: Float): BoundingBox {
            return BoundingBox(
                x1 = cx - w / 2f,
                y1 = cy - h / 2f,
                x2 = cx + w / 2f,
                y2 = cy + h / 2f
            )
        }
    }
}

/**
 * Simple 2D point.
 */
data class Point(
    val x: Float,
    val y: Float
)

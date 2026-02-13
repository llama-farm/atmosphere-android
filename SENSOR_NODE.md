# Android as a Sensor Node

## Overview

The phone is now exposed as a **rich sensor node** on the Atmosphere mesh, not just a compute endpoint. All phone sensors are registered as CRDT capabilities and respond to data requests from mesh peers.

This enables:
- **Mac asks "what's the temperature?"** → Phone's barometer responds
- **Mac asks "what's your location?"** → Phone's GPS responds
- **Mac asks "take a photo"** → Phone's camera responds
- **Bidirectional** - sensors can push data AND respond to requests

## Architecture

```
Mac Peer                          Android Phone
---------                         -------------
1. Insert request into            1. Poll _requests collection
   _requests collection              every 2 seconds
   
2. Wait for response              2. Detect sensor request
   in _responses                     (capability_id = "sensor:*")
                                  
                                  3. SensorRequestHandler reads
                                     sensor data (GPS, temp, etc.)
                                  
                                  4. Insert response into
                                     _responses collection
                                  
3. Receive sensor data            5. Mark request as completed
   from phone                        in _requests
```

## Detected Sensors

### Location (2 sensors)
- **GPS Location** - `sensor:location:gps`
  - Outputs: latitude, longitude, altitude, accuracy, speed, bearing
  - Accuracy: 5-20 meters
  - Requires: ACCESS_FINE_LOCATION

- **Network Location** - `sensor:location:network`
  - Outputs: latitude, longitude, accuracy
  - Accuracy: 100-500 meters  
  - Requires: ACCESS_COARSE_LOCATION

### Motion (6 sensors)
- **Accelerometer** - `sensor:motion:accelerometer`
  - Outputs: x, y, z (m/s²)
  - Detects device acceleration
  
- **Gyroscope** - `sensor:motion:gyroscope`
  - Outputs: x_rotation, y_rotation, z_rotation (rad/s)
  - Detects rotation rate

- **Step Counter** - `sensor:motion:step_counter`
  - Outputs: step_count
  - Requires: ACTIVITY_RECOGNITION

- **Step Detector** - `sensor:motion:step_detector`
  - Outputs: step_event (triggers on each step)

- **Linear Acceleration** - `sensor:motion:linear_acceleration`
  - Outputs: x, y, z (m/s², gravity removed)

- **Gravity** - `sensor:motion:gravity`
  - Outputs: x, y, z (m/s²)

### Environment (4 sensors)
- **Ambient Temperature** - `sensor:environment:temperature`
  - Outputs: temperature (°C)
  - Rare on most phones

- **Relative Humidity** - `sensor:environment:humidity`
  - Outputs: humidity (%)
  - Rare on most phones

- **Barometric Pressure** - `sensor:environment:pressure`
  - Outputs: pressure (hPa), altitude_estimate (m)
  - Available on most flagships

- **Ambient Light** - `sensor:environment:light`
  - Outputs: illuminance (lux)
  - Available on most phones

### Vision (2 sensors)
- **Camera (Back)** - `sensor:camera:back`
  - Outputs: image_frame, video_stream
  - Formats: jpeg, raw, yuv
  - Requires: CAMERA

- **Camera (Front)** - `sensor:camera:front`
  - Outputs: image_frame, video_stream
  - Requires: CAMERA

### Audio (1 sensor)
- **Microphone** - `sensor:audio:microphone`
  - Outputs: audio_stream, audio_level, speech_text
  - Formats: pcm, aac, opus
  - Requires: RECORD_AUDIO

### Battery (1 sensor)
- **Battery Status** - `sensor:battery:status`
  - Outputs: level (%), is_charging, charge_type, health, temperature (°C)
  - Always available, no permissions

### Network (1 sensor)
- **Network Connectivity** - `sensor:network:connectivity`
  - Outputs: connection_type, wifi_ssid, wifi_signal_strength (dBm), cellular_signal_strength
  - Connection types: wifi, cellular, ethernet, vpn, none

### Other (3 sensors)
- **Proximity** - `sensor:proximity`
  - Outputs: distance (cm)
  - Detects nearby objects

- **Magnetometer** - `sensor:magnetometer`
  - Outputs: x, y, z (μT), compass_heading
  - Magnetic field detector, compass

- **Rotation Vector** - `sensor:orientation:rotation_vector`
  - Outputs: azimuth, pitch, roll (degrees)
  - Device orientation in 3D space

## CRDT Capability Format

Each sensor is registered in `_capabilities`:

```json
{
  "_id": "sensor:location:gps",
  "peer_id": "android-pixel9-abc123",
  "peer_name": "Pixel 9 Pro",
  "capability_type": "sensor:location",
  "name": "GPS Location",
  "category": "location",
  "available": true,
  "requires_permission": "android.permission.ACCESS_FINE_LOCATION",
  "device_info": {
    "cpu_cores": 8,
    "memory_gb": 12.0,
    "battery_level": 85,
    "is_charging": false
  },
  "metadata": {
    "provider": "gps",
    "outputs": ["latitude", "longitude", "altitude", "accuracy", "speed", "bearing"],
    "units": {
      "latitude": "degrees",
      "longitude": "degrees",
      "altitude": "meters",
      "accuracy": "meters",
      "speed": "meters/second",
      "bearing": "degrees"
    },
    "typical_accuracy": "5-20 meters",
    "refresh_rate": "1-10 Hz",
    "permission_granted": true
  },
  "cost": {
    "local": true,
    "estimated_cost": 0.0,
    "battery_impact": 0.3
  },
  "status": {
    "available": true,
    "requestable": true,
    "last_seen": 1707857234
  },
  "hops": 0
}
```

## Request/Response Pattern

### Requesting Sensor Data (from Mac)

```javascript
// Insert request into _requests collection
{
  "request_id": "uuid-1234",
  "capability_id": "sensor:location:gps",
  "action": "read",
  "params": {
    "timeout_ms": 5000,
    "require_fresh": true  // Don't use cached location
  },
  "status": "pending",
  "requester_peer_id": "mac-mbp-xyz789"
}
```

### Response (from Phone)

Phone polls `_requests`, detects the sensor request, reads GPS, and inserts response:

```javascript
// Inserted into _responses collection
{
  "request_id": "uuid-1234",
  "status": "success",
  "data": {
    "latitude": 37.7749,
    "longitude": -122.4194,
    "altitude": 15.2,
    "accuracy": 8.5,
    "speed": 2.1,
    "bearing": 45.0,
    "timestamp": 1707857234567,
    "provider": "gps"
  },
  "timestamp": 1707857234
}
```

### Error Response

If permission denied or sensor unavailable:

```javascript
{
  "request_id": "uuid-1234",
  "status": "error",
  "error": "Location permission not granted",
  "timestamp": 1707857234
}
```

## Battery Impact Estimates

Sensor battery impact (0.0 = none, 1.0 = max):

| Sensor Category | Battery Impact | Notes |
|----------------|----------------|-------|
| Location (GPS) | 0.3 | High when active |
| Motion | 0.1 | Very low power |
| Environment | 0.1 | Very low power |
| Vision (Camera) | 0.4 | High when capturing |
| Audio (Mic) | 0.3 | Medium when recording |
| Battery | 0.0 | No impact |
| Network | 0.1 | Low impact |
| Proximity | 0.1 | Very low power |
| Magnetometer | 0.1 | Very low power |

## Timeouts

- **Default timeout**: 5 seconds
- **Max sensor wait**: 10 seconds
- **Request polling interval**: 2 seconds

If a sensor doesn't produce data within the timeout, the request fails with a timeout error.

## Permissions

Sensors automatically check permissions before reading:

| Sensor | Required Permission |
|--------|-------------------|
| GPS Location | ACCESS_FINE_LOCATION |
| Network Location | ACCESS_COARSE_LOCATION |
| Step Counter | ACTIVITY_RECOGNITION |
| Camera | CAMERA |
| Microphone | RECORD_AUDIO |
| WiFi SSID | ACCESS_WIFI_STATE |

If permission is missing:
- Sensor is registered with `available: false` or `permission_granted: false`
- Request returns error: "Permission not granted"

## Example Use Cases

### 1. Location Tracking
Mac monitors phone's location in real-time:
```bash
# Every 10 seconds
curl -X POST http://localhost:14345/api/crdt/_requests -d '{
  "request_id": "loc-$(date +%s)",
  "capability_id": "sensor:location:gps",
  "action": "read"
}'

# Check _responses for GPS coordinates
curl http://localhost:14345/api/crdt/_responses | jq
```

### 2. Environment Monitoring
Mac asks phone for temperature/pressure:
```bash
curl -X POST http://localhost:14345/api/crdt/_requests -d '{
  "request_id": "env-temp",
  "capability_id": "sensor:environment:pressure"
}'

# Response includes pressure (hPa) and altitude estimate
```

### 3. Motion Detection
Mac queries phone's accelerometer:
```bash
curl -X POST http://localhost:14345/api/crdt/_requests -d '{
  "request_id": "motion-1",
  "capability_id": "sensor:motion:accelerometer"
}'

# Response includes x, y, z acceleration values
```

### 4. Battery Monitoring
Mac checks phone's battery status:
```bash
curl -X POST http://localhost:14345/api/crdt/_requests -d '{
  "request_id": "bat-1",
  "capability_id": "sensor:battery:status"
}'

# Response includes level, is_charging, temperature
```

## Implementation Details

### Detection

`SensorCapabilityDetector.kt` enumerates all sensors using:
- `SensorManager` - motion, environment, proximity, magnetometer
- `LocationManager` - GPS, network location
- `CameraManager` - front/back cameras
- `PackageManager` - microphone feature
- `BatteryManager` - battery status
- `ConnectivityManager` - network status

### Request Handling

`SensorRequestHandler.kt` handles sensor requests:
1. Parse request from `_requests` collection
2. Check permissions
3. Read sensor data (with timeout)
4. Insert response into `_responses` collection
5. Mark request as completed

### Service Integration

`AtmosphereService.kt`:
- Detects all sensors on startup
- Registers each as CRDT capability
- Polls `_requests` every 2 seconds
- Responds to sensor requests via `SensorRequestHandler`

## Future Enhancements

- **Streaming sensors** - continuous data push (not just request/response)
- **Camera capture** - integrate with CameraCapability for photo/video
- **Audio capture** - integrate with VoiceCapability for STT
- **Geofencing** - trigger events when entering/leaving locations
- **Activity recognition** - walking, driving, still, biking
- **Sensor fusion** - combine accelerometer + magnetometer for true compass heading

## Testing

### Query Phone's Sensors

From Mac:
```bash
# List all phone sensor capabilities
curl http://localhost:14345/api/crdt/_capabilities | \
  jq '.[] | select(.capability_type | startswith("sensor:"))'

# Count sensors by category
curl http://localhost:14345/api/crdt/_capabilities | \
  jq '[.[] | select(.capability_type | startswith("sensor:"))] | group_by(.category) | map({category: .[0].category, count: length})'
```

### Request GPS Location

```bash
# Insert request
curl -X POST http://localhost:14345/api/crdt/_requests -d '{
  "request_id": "test-gps-'$(date +%s)'",
  "capability_id": "sensor:location:gps",
  "action": "read",
  "status": "pending"
}'

# Wait 2-5 seconds, then check responses
curl http://localhost:14345/api/crdt/_responses | jq
```

### Android Logs

```bash
# Monitor sensor detection and request handling
adb logcat -s SensorCapability SensorRequestHandler AtmosphereService

# Expected logs:
# "Detected 23 sensor capabilities"
# "location: 2, motion: 6, environment: 4, ..."
# "Handling sensor request: sensor:location:gps"
# "Sensor response sent for test-gps-1234: 156 bytes"
```

---

## Summary

The phone is now a **first-class sensor node** on the mesh:
- 20+ sensors automatically detected and registered
- Request/response pattern via CRDT `_requests` / `_responses`
- Permissions checked automatically
- Timeout handling (5-10s)
- Battery impact metadata included
- Works alongside AI capabilities (Llama, Gemini, embeddings)

**The mesh is now heterogeneous**: compute (LLMs) + data (sensors) + storage!

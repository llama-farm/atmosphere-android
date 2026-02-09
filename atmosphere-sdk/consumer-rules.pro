# Consumer ProGuard rules for Atmosphere SDK
# These rules are applied to apps that use the SDK

# Keep AIDL interfaces
-keep class com.llamafarm.atmosphere.IAtmosphereService { *; }
-keep class com.llamafarm.atmosphere.IAtmosphereService$Stub { *; }
-keep class com.llamafarm.atmosphere.IAtmosphereService$Stub$Proxy { *; }
-keep class com.llamafarm.atmosphere.IAtmosphereCallback { *; }
-keep class com.llamafarm.atmosphere.IAtmosphereCallback$Stub { *; }

# Keep Parcelable classes
-keep class com.llamafarm.atmosphere.AtmosphereCapability { *; }
-keepclassmembers class com.llamafarm.atmosphere.AtmosphereCapability {
    public static final android.os.Parcelable$Creator CREATOR;
}

# Keep SDK public API
-keep class com.llamafarm.atmosphere.sdk.AtmosphereClient { *; }
-keep class com.llamafarm.atmosphere.sdk.AtmosphereClient$Companion { *; }
-keep class com.llamafarm.atmosphere.sdk.ServiceConnector { *; }

# Keep data classes
-keep class com.llamafarm.atmosphere.sdk.ChatMessage { *; }
-keep class com.llamafarm.atmosphere.sdk.RouteResult { *; }
-keep class com.llamafarm.atmosphere.sdk.ChatResult { *; }
-keep class com.llamafarm.atmosphere.sdk.Capability { *; }
-keep class com.llamafarm.atmosphere.sdk.MeshStatus { *; }
-keep class com.llamafarm.atmosphere.sdk.CostMetrics { *; }
-keep class com.llamafarm.atmosphere.sdk.MeshJoinRequest { *; }
-keep class com.llamafarm.atmosphere.sdk.MeshJoinResult { *; }

# Keep exceptions
-keep class com.llamafarm.atmosphere.sdk.AtmosphereNotInstalledException { *; }
-keep class com.llamafarm.atmosphere.sdk.AtmosphereNotConnectedException { *; }

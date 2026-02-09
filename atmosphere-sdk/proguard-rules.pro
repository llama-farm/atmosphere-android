# ProGuard rules for Atmosphere SDK library build

# Keep AIDL generated code
-keep class com.llamafarm.atmosphere.IAtmosphereService$** { *; }
-keep class com.llamafarm.atmosphere.IAtmosphereCallback$** { *; }

# Keep Parcelable CREATOR fields
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

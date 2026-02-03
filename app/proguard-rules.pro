# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# Atmosphere Android ProGuard/R8 Rules
# =====================================

# Keep native method names for JNI
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Rust JNI interface classes
-keep class com.llamafarm.atmosphere.rust.** { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Compose specific rules
-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }

# Keep data classes for serialization
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep model classes (adjust package as needed)
-keep class com.llamafarm.atmosphere.data.model.** { *; }

# Lifecycle
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keep class * extends androidx.lifecycle.AndroidViewModel { *; }

# Suppress warnings for missing classes in release builds
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**

# Optimization settings
-optimizationpasses 5
-allowaccessmodification
-dontusemixedcaseclassnames
-verbose

# Keep source file names and line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Remove logging in release builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

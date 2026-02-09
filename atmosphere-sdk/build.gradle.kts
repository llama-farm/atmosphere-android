plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("maven-publish")
}

android {
    namespace = "com.llamafarm.atmosphere.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        
        // Version info for AAR
        buildConfigField("String", "SDK_VERSION", "\"1.0.0\"")
        buildConfigField("int", "SDK_VERSION_CODE", "1")
    }
    
    buildFeatures {
        aidl = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            // Generate documentation
            freeCompilerArgs.add("-Xopt-in=kotlin.RequiresOptIn")
        }
    }
    
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.json:json:20230227")
    
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}

// Maven publishing configuration
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.llamafarm"
            artifactId = "atmosphere-sdk"
            version = "1.0.0"
            
            afterEvaluate {
                from(components["release"])
            }
            
            pom {
                name.set("Atmosphere SDK")
                description.set("Client SDK for using the Atmosphere mesh network from Android apps")
                url.set("https://github.com/llamafarm/atmosphere-android")
                
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                
                developers {
                    developer {
                        id.set("llamafarm")
                        name.set("LlamaFarm")
                        email.set("dev@llamafarm.com")
                    }
                }
                
                scm {
                    connection.set("scm:git:git://github.com/llamafarm/atmosphere-android.git")
                    developerConnection.set("scm:git:ssh://github.com:llamafarm/atmosphere-android.git")
                    url.set("https://github.com/llamafarm/atmosphere-android")
                }
            }
        }
    }
    
    repositories {
        maven {
            name = "local"
            url = uri("${project.buildDir}/repo")
        }
        // For actual Maven Central publishing, add credentials:
        // maven {
        //     name = "mavenCentral"
        //     url = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
        //     credentials {
        //         username = project.findProperty("ossrhUsername") as String? ?: ""
        //         password = project.findProperty("ossrhPassword") as String? ?: ""
        //     }
        // }
    }
}

plugins {
    id("com.android.library")
}

android {
    namespace = "org.amnezia.awg.tunnel"
    compileSdk = 35

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            // Primary phone + emulator; add armeabi-v7a later if needed
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    ndkVersion = "27.0.12077973"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path("tools/CMakeLists.txt")
        }
    }

    buildTypes {
        all {
            externalNativeBuild {
                cmake {
                    targets("libwg-go.so")
                    arguments("-DGRADLE_USER_HOME=${project.gradle.gradleUserHomeDir}")
                }
            }
        }
        release {
            externalNativeBuild {
                cmake {
                    // UAPI socket path under our applicationId
                    arguments("-DANDROID_PACKAGE_NAME=com.ardtt.app")
                }
            }
        }
        debug {
            externalNativeBuild {
                cmake {
                    arguments("-DANDROID_PACKAGE_NAME=com.ardtt.app")
                }
            }
        }
    }

    lint {
        disable += "LongLogTag"
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.7.1")
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
}

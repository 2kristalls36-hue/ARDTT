import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

val targetAbis = providers.gradleProperty("targetAbis")
    .map { value -> value.split(',').map(String::trim).filter { it.isNotEmpty() } }
    .orElse(listOf("arm64-v8a", "armeabi-v7a", "x86_64"))

// Public Releases need no PAT. Optional override for private forks only.
val githubReleaseToken = providers.gradleProperty("githubReleaseToken")
    .orElse(providers.environmentVariable("GITHUB_RELEASE_READ_TOKEN"))
    .orElse("")
    .get()
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

android {
    namespace = "com.ardtt.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ardtt.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 267
        versionName = "0.5.249"
        buildConfigField(
            "String",
            "TELEMETRY_UPLOAD_URL",
            "\"https://45.129.2.3/api/upload-log\"",
        )
        buildConfigField(
            "String",
            "UPDATE_MANIFEST_URL",
            "\"https://45.129.2.3/update.json\"",
        )
        buildConfigField(
            "String",
            "GITHUB_REPO_OWNER",
            "\"2kristalls36-hue\"",
        )
        buildConfigField(
            "String",
            "GITHUB_REPO_NAME",
            "\"ARDTT\"",
        )
        buildConfigField(
            "String",
            "GITHUB_API_TOKEN",
            "\"$githubReleaseToken\"",
        )
    }

    ndkVersion = "27.0.12077973"

    splits {
        abi {
            isEnable = true
            reset()
            include(*targetAbis.get().toTypedArray())
            isUniversalApk = targetAbis.get().size > 1
        }
    }

    signingConfigs {
        create("release") {
            val storeFileProp = keystoreProperties.getProperty("storeFile")
            val storePasswordProp = keystoreProperties.getProperty("storePassword")
            val keyAliasProp = keystoreProperties.getProperty("keyAlias")
            val keyPasswordProp = keystoreProperties.getProperty("keyPassword")
            check(
                !storeFileProp.isNullOrBlank() &&
                    !storePasswordProp.isNullOrBlank() &&
                    !keyAliasProp.isNullOrBlank() &&
                    !keyPasswordProp.isNullOrBlank(),
            ) {
                "Missing android/keystore.properties (fetch from VPS secrets or run scripts/fetch-release-keystore.sh)"
            }
            storeFile = rootProject.file(storeFileProp)
            storePassword = storePasswordProp
            keyAlias = keyAliasProp
            keyPassword = keyPasswordProp
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

val repoRoot = rootProject.projectDir.parentFile
val bypassClientLib = file("src/main/jniLibs/arm64-v8a/libclient.so")
val buildBypassClient = tasks.register<Exec>("buildBypassClient") {
    onlyIf { !bypassClientLib.exists() }
    workingDir = repoRoot
    commandLine("bash", "scripts/build-bypass-client.sh")
}

tasks.named("preBuild") {
    dependsOn(buildBypassClient)
}

dependencies {
    implementation(project(":tunnel"))

    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-process:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.github.mwiede:jsch:0.2.21")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
}

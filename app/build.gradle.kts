import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ─── Release signing ──────────────────────────────────────────────────────────
// Create keystore.properties (see keystore.properties.example) to enable signed builds.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) load(keystorePropsFile.inputStream())
}

android {
    namespace = "eus.basaundi.taukitauki"
    compileSdk = 35

    defaultConfig {
        applicationId = "eus.basaundi.taukitauki"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.0.4"
    }

    if (keystorePropsFile.exists()) {
        signingConfigs {
            create("release") {
                keyAlias     = keystoreProps["keyAlias"]     as String
                keyPassword  = keystoreProps["keyPassword"]  as String
                storeFile    = file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Fall back to debug signing so AGP always places the APK in the
            // standard outputs/ directory. F-Droid strips and re-signs anyway.
            signingConfig = if (keystorePropsFile.exists())
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    lint {
        // lintVital uses a JVM version parser that chokes on Java 26 locally;
        // F-Droid builds on Java 17/21 where this is fine.
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}

// ─── Dictionary database generation ──────────────────────────────────────────
// Runs make_dictionary.py to produce app/src/main/assets/dict.db from data/*.json.
// Re-runs only when a source JSON file changes (Gradle up-to-date checks).
val generateDict by tasks.registering(Exec::class) {
    group = "build"
    description = "Build dict.db from data/*.json via make_dictionary.py"
    inputs.files(fileTree(rootProject.projectDir.resolve("data")) { include("*.json") })
    outputs.file(layout.projectDirectory.file("src/main/assets/dict.db"))
    commandLine("python3", rootProject.projectDir.resolve("make_dictionary.py").absolutePath)
}

afterEvaluate {
    tasks.matching { task ->
        val n = task.name
        (n.startsWith("merge") && n.endsWith("Assets")) ||
        n.contains("lint", ignoreCase = true)
    }.configureEach { dependsOn(generateDict) }
}

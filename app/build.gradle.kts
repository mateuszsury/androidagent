plugins {
    id("com.android.application")
}

// Version lives in BuildInfo.java so the Gradle and on-device (build.sh) builds always agree.
val buildInfo = file("src/main/java/dev/mateuszsury/androidagent/BuildInfo.java").readText()
val appVersionName = Regex("""VERSION = "([^"]+)"""").find(buildInfo)!!.groupValues[1]
val appVersionCode = Regex("""VERSION_CODE = (\d+)""").find(buildInfo)!!.groupValues[1].toInt()

android {
    namespace = "dev.mateuszsury.androidagent"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.mateuszsury.androidagent"
        minSdk = 30
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    // Release signing comes from environment variables (set as GitHub Actions secrets).
    // Without them, assembleRelease produces an unsigned APK.
    val keystorePath = System.getenv("BUDDY_KEYSTORE")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("BUDDY_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("BUDDY_KEY_ALIAS") ?: "buddy"
                keyPassword = System.getenv("BUDDY_KEY_PASSWORD") ?: System.getenv("BUDDY_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildFeatures {
        buildConfig = false
    }
    packaging {
        jniLibs.useLegacyPackaging = true
    }
    androidResources {
        // The guest disks are already gzip-compressed; match build.sh and avoid
        // another decompression layer when installing the Linux environment.
        noCompress += "gz"
        noCompress += "zip"
    }
}

// Both build paths require verified USB/ESP and QEMU artifacts.
val verifyUsbAssets by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("python3", "tools/check-usb-assets.py")
}
val verifyDockerAssets by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("python3", "tools/check-docker-assets.py")
}
val verifyTerminalAssets by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("python3", "tools/check-terminal-assets.py")
}
tasks.named("preBuild") { dependsOn(verifyUsbAssets, verifyDockerAssets, verifyTerminalAssets) }

# jros2 Cellphone Interface

Android (Jetpack Compose) application that publishes and subscribes to ROS 2 topics from a real phone using the IHMC `jros2-android` stack (Fast DDS + JavaCPP JNI).

## What This Project Does

- Creates an Android ROS 2 node (`android_chatter_node`).
- Publishes messages to `/chatter` at 1 Hz.
- Subscribes to `/chatter` and shows received messages in-app.
- Uses a Compose UI with independent Start/Stop toggles for publisher and listener.
- Keeps DDS discovery working on Android Wi-Fi by acquiring a multicast lock.

## Architecture

### High-level Layers

1. UI Layer (Compose)
- `MainActivity` hosts the app screen and button-driven state (`isPublishing`, `isSubscribing`).
- Received and sent messages are shown in a scrolling console.

2. Application/Concurrency Layer
- Main thread scope updates UI state.
- IO scope runs ROS 2 work (node creation, publish loop, subscription callbacks).
- Publisher loop sends one message per second while enabled.

3. ROS 2 Integration Layer
- `ROS2Node` manages participant/session lifecycle.
- `ROS2Publisher<std_msgs/String>` sends chatter messages.
- `ROS2Subscription<std_msgs/String>` receives messages.

4. Native/JNI Layer
- `jros2-android` provides JavaCPP bindings (`fastddsjava`) and native libs:
  - `libjnifastddsjava.so`
  - `libfastdds.so`
  - `libfastcdr.so`

5. Network Layer
- DDS discovery and traffic over Wi-Fi/UDP.
- Android multicast lock is required for discovery reliability.

### Runtime Flow

- App start:
  - Acquire Wi-Fi multicast lock.
  - Create ROS 2 node and `/chatter` publisher/subscriber.
- Publish toggle ON:
  - Background coroutine publishes `"Hello from Android: N"` every second.
- Subscribe toggle ON:
  - Incoming samples are read from ROS 2 callback path and appended to UI list.
- App stop/destroy:
  - Cancel coroutines, close node, release multicast lock.

## Repository Layout

```text
jros2_cellphone_interface/
  app/
    src/main/java/com/jros2/cellphone_interface/MainActivity.kt
    src/main/AndroidManifest.xml
    build.gradle.kts
  build.gradle.kts
  settings.gradle.kts
  gradle.properties
```

## Prerequisites

- JDK 17 (required by AGP/Gradle in this project).
- Android SDK platform used by this app (`compileSdk = 36`, `minSdk = 33`).
- Local sibling checkout of `jros2` (same parent workspace).

## Build Instructions

### 1) Publish patched `jros2-android` to Maven Local

Important: publish from the Android module (`jros2/android`), not only from root `jros2`.

PowerShell:

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
$env:Path="$env:JAVA_HOME\bin;$env:Path"

cd ..\jros2\android
..\gradlew -p . publishReleasePublicationToMavenLocal
```

### 2) Build this Android app

```powershell
cd ..\jros2_cellphone_interface
.\gradlew assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### 3) Install on phone

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## Issues We Hit and How They Were Fixed

This section documents the real problems encountered while bringing ARM64 phone support online.

### 1) Gradle/AGP Java mismatch

Symptom:
- Build/publish failed with message equivalent to: "Gradle requires JVM 17 or later", while environment used Java 15.

Fix:
- Forced Gradle to use Android Studio JBR 17 via:
  - `gradle.properties` (`org.gradle.java.home=...`) and/or `JAVA_HOME` in shell.

### 2) App was not consuming the newly patched Android artifact

Symptom:
- Runtime behavior did not change after code fixes in `jros2`.

Root cause:
- Publishing root `jros2` does not automatically guarantee updated `jros2-android` AAR content.

Fix:
- Published from `jros2/android` using `publishReleasePublicationToMavenLocal`.
- Kept `mavenLocal()` enabled in this app (`settings.gradle.kts`).

### 3) ARM64 `UnsatisfiedLinkError` for Fast DDS JNI symbols

Symptoms on real phone:
- Missing implementations such as:
  - `fastddsjava_datareader_read_next_sample(...)`
  - `fastddsjava_sampleinfo_valid_data(...)`

Root cause:
- ARM64 native JNI library lacked some symbols available on x86_64 builds.

Fixes applied in `jros2` Java layer:
- In `ROS2Subscription`:
  - Fallback from `read_next_sample` to `read_next_custom` on `UnsatisfiedLinkError`.
  - Fallback for `sampleinfo_valid_data` check (assume valid when method missing).
  - Fallback timestamp collection to `System.currentTimeMillis()` if sampleinfo timestamp JNI methods are missing.
- In `fastddsjava.java`:
  - Added binding for `fastddsjava_datareader_read_next_custom(...)`.

Result:
- The subscription path no longer hard-crashes when those specific ARM64 JNI symbols are absent.

### 4) DDS discovery unreliability on Android Wi-Fi

Symptom:
- ROS 2 peers were not discovered consistently.

Fix:
- Enabled and acquired Android `WifiManager.MulticastLock`.
- Ensured permissions include `CHANGE_WIFI_MULTICAST_STATE`.

### 5) Dependency resolution/network constraints

Symptom:
- `--refresh-dependencies` failed with DNS/host errors (e.g. unable to resolve `repo.maven.apache.org`).

Fix/Workaround:
- Build without forced refresh when local caches are valid.
- Ensure proxy/DNS access before forcing dependency refresh.

## Known Limitations

- Current ARM64 workaround is defensive at Java level; ideal long-term fix is to regenerate/rebuild ARM64 JNI binaries with full symbol parity.
- If dependencies are not already cached, network/proxy configuration is required for fresh Gradle resolution.

## Verification Checklist

- `jros2-android` published locally from `jros2/android`.
- This app builds with `assembleDebug`.
- Phone installs APK and opens app.
- Pressing Publish sends messages repeatedly without JNI crash.
- Listener receives ROS 2 chatter from external node (WSL2/Linux).

## Main Dependencies

- AndroidX + Compose Material3
- Kotlin Coroutines Android
- `us.ihmc:jros2-android:1.1.6`
- `us.ihmc:log-tools:0.6.5`

## License / Upstream

`jros2` and related native wrappers come from IHMC ecosystem components. Check upstream `jros2` repository licensing and notices for distribution/compliance details.

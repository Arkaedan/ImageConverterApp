# Image Converter

A simple, native Android app for converting images between formats, built with Kotlin, Jetpack Compose
and Material You.

## Features

- **Convert to** JPEG, PNG, WebP (lossy or lossless), HEIF, AVIF, GIF and BMP
- **Convert from** anything Android can decode (JPEG, PNG, WebP, HEIF, AVIF, GIF, BMP, DNG, ...) plus **SVG**
- Batch conversion of multiple images at once
- Quality control for lossy formats and optional downscaling (or SVG render size) by longest side
- Share images from other apps (Photos, Files, ...) straight into the converter, and share the results out
- Material You dynamic colour, light/dark theme, and a two-pane layout on tablets and in landscape

Converted images are saved to **Pictures/Image Converter**, so they show up in your gallery. Original
files are never modified.

### Format notes

- **HEIF** and **AVIF** are encoded with the device's hardware/software codecs. If a device has no
  suitable encoder, that format is shown as unavailable.
- **JPEG, HEIF and AVIF** don't support transparency, so transparent areas become white.
- **GIF** is limited to 256 colours; photos are dithered. Only still images are written, and animated
  inputs are converted from their first frame.
- **BMP** files are uncompressed and can be very large.
- **SVG** files are rendered at the selected size. *Original* uses the size declared in the file.
- Metadata such as EXIF and location is not copied to converted files.

## Requirements

- Android 12 (API 31) or newer
- To build: JDK 17+ and the Android SDK (API 37 platform)

## Building

Create `local.properties` pointing at your Android SDK (Android Studio does this automatically):

```properties
sdk.dir=C\:/Users/<you>/AppData/Local/Android/Sdk
```

Then:

```bash
./gradlew assembleRelease    # app/build/outputs/apk/release/app-release.apk
./gradlew installRelease     # build and install on a connected device
./gradlew testDebugUnitTest  # unit tests
```

Release builds are minified. They are signed with the upload key when `keystore.properties` exists
(see below), and with the debug key otherwise so they can still be sideloaded.

## Publishing

The Play Store rejects debug-signed builds, so create an upload key once and keep a backup of it outside
the repo:

```bash
keytool -genkeypair -v -keystore E:/keys/imageconverter-upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
```

Then create `keystore.properties` in the repo root (it is git-ignored):

```properties
storeFile=E:/keys/imageconverter-upload.jks
storePassword=<keystore password>
keyAlias=upload
keyPassword=<key password>
```

Bump `versionCode` in `app/build.gradle.kts` for every upload, then build the bundle to upload to Play
Console:

```bash
./gradlew bundleRelease      # app/build/outputs/bundle/release/app-release.aab
```

## Installing on a device

1. Enable **Developer options** and **USB debugging** (or **Wireless debugging**) on the device.
2. Connect it and accept the debugging prompt. `adb devices` should list it.
3. Run `./gradlew installRelease`, or `adb install -r app/build/outputs/apk/release/app-release.apk`.

## Project structure

See [AGENTS.md](AGENTS.md) for an overview of the code layout and conventions.

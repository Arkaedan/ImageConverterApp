# AGENTS.md

Guidance for AI coding agents working in this repository.

## Project

A native Android app (Kotlin, Jetpack Compose, Material 3 / Material You) that converts images between
formats. Target devices are a Google Pixel 10 Pro and a Samsung Galaxy Tab S9 FE, so the UI must work
on phones and tablets in both orientations. See [SPEC.md](SPEC.md) for the original requirements.

- Single module: `:app`, package `com.arkaedan.imageconverter`
- minSdk 31 (Android 12, for dynamic colour and AVIF decoding), compile/target SDK 37
- AGP 9 with built-in Kotlin (no `kotlin-android` plugin is applied to `:app`), versions live in
  `gradle/libs.versions.toml`

## Commands

Run from the repo root. The build needs JDK 17+ (`JAVA_HOME`) and an Android SDK whose path is in
`local.properties` (`sdk.dir=...`, not committed).

```bash
./gradlew testDebugUnitTest   # JVM unit tests (encoders, helpers)
./gradlew lintDebug           # Android lint; keep it clean
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease     # minified; upload key if keystore.properties exists, else debug key
./gradlew bundleRelease       # app/build/outputs/bundle/release/app-release.aab for Play Console
./gradlew installRelease      # install on the connected device (adb)
```

Run unit tests and lint before considering a change done. Prefer testing on a real device for anything
touching codecs: HEIF/AVIF support depends on the device's MediaCodec encoders.

## Layout

```
app/src/main/java/com/arkaedan/imageconverter/
  MainActivity.kt              Entry point; handles ACTION_SEND / SEND_MULTIPLE share intents
  conversion/
    ImageFormat.kt             Output formats, sizes, ConversionSettings, MIME -> label helper
    ImageLoader.kt             Decoding: ImageDecoder for rasters, AndroidSVG for SVG; thumbnails
    ImageEncoders.kt           Per-format encoding + EncoderSupport (device codec detection)
    ImageConverter.kt          decode -> encode -> save to MediaStore (Pictures/Image Converter)
    BmpEncoder.kt, GifEncoder.kt  Hand-written encoders (Android only decodes BMP/GIF)
    PixelSource.kt             Row-by-row pixel access so encoders stay memory-light
  ui/
    ConverterViewModel.kt      UI state, image probing, conversion queue, persisted settings
    ConverterScreen.kt         The single Compose screen (one pane on phones, two panes >= 720dp)
    theme/Theme.kt             Dynamic colour theme
app/src/test/                  JVM unit tests (no Android framework; use ArrayPixelSource)
```

## How encoding works

| Format | Encoder |
| --- | --- |
| JPEG, PNG, WebP (lossy/lossless) | `Bitmap.compress` |
| HEIF | `androidx.heifwriter.HeifWriter` (needs an HEVC/HEIC encoder) |
| AVIF | `androidx.heifwriter.AvifWriter` (needs an AV1 encoder; hidden when absent) |
| GIF | `GifEncoder`: median-cut palette, Floyd–Steinberg dithering, LZW |
| BMP | `BmpEncoder`: 24-bit, or 32-bit BGRA with a V4 header when the image has alpha |

Formats without transparency (JPEG, HEIF, AVIF) are flattened onto white. Display P3 images keep their
colour space for JPEG/PNG/WebP; everything else is converted to sRGB. Metadata (EXIF) is not copied,
although EXIF orientation is applied to the pixels on decode.

## Conventions

- Kotlin official code style, 4-space indent, ~120-column lines. Match the surrounding code's comment
  density: KDoc on types and non-obvious functions, sparse inline comments that explain *why*.
- All user-visible text goes in `res/values/strings.xml` (use plurals for counts). In Compose, read
  resources via `stringResource` / `LocalResources`, not `LocalContext.current.resources` (lint error).
- Keep the UI simple and Material 3: dynamic colour only, M3 components, no custom colour palettes.
- Keep dependencies minimal. Icons come from `material-icons-core` or vector drawables in `res/drawable`
  (do not add `material-icons-extended`).
- Encoders that are pure Kotlin should take a `PixelSource` and be covered by JVM unit tests.
- Heavy work runs off the main thread (`Dispatchers.Default`/`IO`); conversions run one image at a time
  to bound memory, since a single 50 MP bitmap is ~200 MB.
- Use stable library versions only, and update `libs.versions.toml` rather than hard-coding versions.

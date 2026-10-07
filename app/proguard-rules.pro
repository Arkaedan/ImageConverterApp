# AndroidSVG ships without consumer rules; keep it intact (it is small and uses reflection-free parsing,
# but its element classes are referenced by name in error messages and CSS handling).
-keep class com.caverock.androidsvg.** { *; }
-dontwarn com.caverock.androidsvg.**

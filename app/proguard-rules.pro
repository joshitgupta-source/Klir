# Keep classes and methods annotated with @Keep
-keep,allowobfuscation @interface androidx.annotation.Keep
-keep @androidx.annotation.Keep class * {*;}
-keepclasseswithmembers class * {
    @androidx.annotation.Keep <methods>;
}

# Explicitly preserve Klir's Rust JNI bridge in StorageCleanerEngine
-keepclasseswithmembernames,includedescriptorclasses class com.joshit.klir.StorageCleanerEngine {
    native <methods>;
}

# Preserve data models used across UI state
-keep class com.joshit.klir.JunkType { *; }
-keep class com.joshit.klir.JunkItem { *; }
-keep class com.joshit.klir.DiskNode { *; }
-keep class com.joshit.klir.TrashItem { *; }

# Preserve WorkManager CoroutineWorker and Quick Settings TileService in release builds
-keep class com.joshit.klir.KlirMaintenanceWorker { *; }
-keep class com.joshit.klir.KlirQuickTileService { *; }

-keep class com.joshit.klir.StorageVolumeInfo { *; }
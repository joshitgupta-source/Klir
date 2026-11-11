# Preserve native JNI engine and native method symbols
-keep class com.joshit.klir.StorageCleanerEngine { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Preserve WorkManager Worker reflection constructor
-keep class com.joshit.klir.KlirMaintenanceWorker {
    public <init>(...);
}

# Fix startup crash: preserve WorkDatabase_Impl constructor without broad rules
-keep class androidx.work.impl.*Database_Impl {
    <init>();
}

# Preserve Klir data models
-keep class com.joshit.klir.StorageVolumeInfo { *; }
-keep class com.joshit.klir.JunkItem { *; }
-keep class com.joshit.klir.TrashItem { *; }
-keep class com.joshit.klir.DiskNode { *; }
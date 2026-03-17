# DexRadar ProGuard / R8 Rules
-keep class com.dexradar.MainApplication { *; }
-keep class com.dexradar.MainActivity { *; }
-keep class com.dexradar.vpn.** { *; }
-keep class com.dexradar.overlay.** { *; }
-keep class com.dexradar.model.** { *; }
-keep class com.dexradar.data.** { *; }
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
-keepattributes *Annotation*
-keepattributes Kotlin*
-keepattributes SourceFile,LineNumberTable
-keep class android.net.VpnService { *; }
-dontwarn kotlin.reflect.jvm.internal.**
-dontwarn org.jetbrains.annotations.**

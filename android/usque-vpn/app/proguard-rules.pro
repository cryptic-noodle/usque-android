# Keep Go Mobile JNI bindings and interfaces
-keep class go.** { *; }
-keep class usqueandroid.** { *; }

# Keep Android VpnService and components
-keep public class * extends android.net.VpnService
-keep public class * extends android.app.Activity

# Optimize Kotlin intrinsics and coroutines
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkNotNullParameter(java.lang.Object, java.lang.String);
}

# Keep Mozilla GeckoView JNI classes, child services & delegates intact
-keep class org.mozilla.** { *; }
-keep class mozilla.** { *; }
-dontwarn org.mozilla.**
-dontwarn mozilla.**

# GeckoView transitively bundles SnakeYAML which references desktop-only java.beans.*
-dontwarn java.beans.**
-dontwarn org.yaml.snakeyaml.**

# Keep AndroidX FileProvider & app components
-keep class androidx.core.content.FileProvider { *; }
-keep class com.omegax.antigravityremote.AntigravityApp { *; }
-keep class com.omegax.antigravityremote.MainActivity { *; }
-keep class com.omegax.antigravityremote.CrashActivity { *; }
-keep class com.omegax.antigravityremote.KeepAliveService { *; }

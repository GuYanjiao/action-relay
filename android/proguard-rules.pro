# JNI exports are linked by the existing stable class/method names.
-keep class cn.local.actionrelay.core.NativeCore { *; }
# Xiaomi SDK uses service binding and reflective API bridges.
-keep class com.xiaomi.xms.wearable.** { *; }
-keep interface com.xiaomi.xms.wearable.** { *; }
-dontwarn javax.annotation.**

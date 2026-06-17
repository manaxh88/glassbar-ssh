# JSch - keep all classes (uses heavy reflection)
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# JCE / JSch crypto
-keepclassmembers class * {
    @javax.crypto.* <fields>;
}
-keep class javax.crypto.** { *; }
-keep class com.sun.crypto.** { *; }
-keepclassmembers class com.jcraft.jsch.** { *; }

# Keep all JSch-related stuff
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses

# Keep this file for future release build rules.

# Eclipse Music - Keep core classes
-keep class com.eclipseapp.pulse.** { *; }
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class com.newpipe.** { *; }
-dontwarn okhttp3.**
-dontwarn org.schabi.**
-keepattributes Signature,*Annotation*

# Suppress missing class warnings from Rhino/NewPipe dependencies
-dontwarn java.beans.**
-dontwarn org.mozilla.javascript.**
-dontwarn javax.script.**
-dontwarn java.awt.**
-dontwarn javax.swing.**
-dontwarn org.graalvm.**
-dontwarn com.oracle.**

# Suppress jsoup re2j optional dependency
-dontwarn com.google.re2j.**
-dontwarn org.jsoup.**

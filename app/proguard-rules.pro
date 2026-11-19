# Fork R8 rules for the release build.
#
# Extensions are separate APKs compiled against this app and linked
# by name at runtime (PluginManager loads their classes, they
# subclass MainAPI / call into com.lagradost.**). They also link
# against the app's bundled libraries (Kotlin stdlib, OkHttp, Jsoup,
# Jackson, ...) by name, because plugins are built against the
# unobfuscated app as their classpath.
#
# v4.9.10/v4.9.11 broke every plugin install ("Unable to load
# plugin") because R8 renamed all of those library classes; only
# com.lagradost.** had been kept. The fix, verified by diffing the
# referenced class sets of all 20 plugins of a real repository
# against the v4.9.8 (pre-R8) and v4.9.11 APKs:
#   1. -dontobfuscate: never rename anything, ever.
#   2. Keep the plugin-facing library surface below from shrinking
#      too, so classes the app itself does not call but plugins do
#      (e.g. okhttp3.Interceptor) still exist.
# Shrinking still applies to everything else (Compose, Media3
# internals, Coil, ...), which is where most of the size win lives.

-dontobfuscate

-keep class com.lagradost.** { *; }
-keep interface com.lagradost.** { *; }
-keep enum com.lagradost.** { *; }
-keep class com.lagradost.**$* { *; }

# Plugin-facing libraries: plugins link against these by name.
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class org.jsoup.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class com.google.gson.** { *; }
-keep class org.jetbrains.annotations.** { *; }

# JNI: native method names must survive (QuickJS and friends).
-keepclasseswithmembernames class * {
    native <methods>;
}

# Media3 optional extractors the player factory probes reflectively.
-keep class androidx.media3.decoder.midi.** { *; }
-keep class androidx.media3.decoder.flac.** { *; }

# Parcelable creators, in case any library model rides a Bundle.
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep annotations and signatures for serialization frameworks.
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault

# kotlinx.serialization generated serializers outside com.lagradost.
-keep class **$$serializer { *; }
-keep class **$*$$serializer { *; }

# Rhino (org.mozilla.javascript) optionally uses the desktop-only
# java.beans introspection API, which does not exist on Android; the
# references are guarded at runtime. Without this, R8 aborts with
# "Missing class java.beans.*" errors.
-dontwarn java.beans.**

# Fork R8 rules for the release build.
#
# Extensions are separate APKs compiled against this app's public API
# and linked by name at runtime (PluginManager loads their classes,
# they subclass MainAPI / call into com.lagradost.**). Obfuscating or
# removing app classes would break every installed extension, so all
# com.lagradost.** names and members are kept. Shrinking still
# applies in full to third-party libraries (Compose, Media3, OkHttp,
# Jackson, ...), which make up most of the dex size.

-keep class com.lagradost.** { *; }
-keep interface com.lagradost.** { *; }
-keep enum com.lagradost.** { *; }
-keep class com.lagradost.**$* { *; }

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

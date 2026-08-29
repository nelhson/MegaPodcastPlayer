# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# --- BPodcat ---------------------------------------------------------------
# Retrofit + kotlinx.serialization DTOs are only referenced reflectively at the HTTP boundary.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-dontwarn org.slf4j.**

# --- NewPipeExtractor (YouTube audio resolution) ----------------------------
# The YouTube extractor runs YouTube's own player JavaScript through Rhino to decipher the
# throttling ("n") parameter, and Rhino resolves its classes reflectively. These are the rules
# NewPipe's own README mandates. They are necessary but, as the block at the end of this file
# records, not sufficient on their own.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
-dontwarn org.schabi.newpipe.extractor.**
-dontwarn org.jsoup.**
-dontwarn com.grack.nanojson.**

# Rhino ships a JSR-223 script-engine wrapper and an invokedynamic-based optimiser. Neither
# javax.script nor jdk.dynalink exists on Android, and neither code path is reachable there — Rhino
# falls back to its interpreter. R8 only notices because the -keep above stops it discarding those
# classes, so silence the dangling references rather than dropping the keep the extractor needs.
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
# Same again for java.beans: Rhino's Java-to-JSON converter reaches for Introspector, which
# Android does not ship. That converter is only used by Rhino's own JSON bridge, which the
# YouTube extractor never calls.
-dontwarn java.beans.**

# The extractor must survive R8 intact. Verified the hard way: with only the Rhino rules above, the
# release build compiles and extraction *appears* to run, but comes back with zero audio streams and
# playback fails with "no downloadable audio track" — while the identical debug build plays fine.
# The extractor's parsing is driven by class and member shapes that R8 rewrites, so keep it whole.
# Costs roughly 1 MB; there is no smaller rule that has been shown to work.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.jsoup.** { *; }
-keep class com.grack.nanojson.** { *; }

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

# Repackage all obfuscated classes into the root package to minimize package name strings in DEX
-repackageclasses ''
-allowaccessmodification

# Preserve line numbers and source file attribute for readable crash traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# OkHttp optional TLS providers (not used in this build)
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# MediaPipe tasks-audio: native JNI bindings, Graph framework, and Flogger stack-walking
-dontwarn com.google.mediapipe.proto.**
-dontwarn com.google.auto.value.extension.memoized.**
-keep class com.google.mediapipe.** { *; }
-keep class com.google.common.flogger.** { *; }

# Protobuf Lite reflection field access (required for MediaPipe tasks-audio / YAMNet)
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
  <fields>;
}

# Keep SoundClassifier and LiveSession data models against reflection/R8 issues
-keep class com.soundsight.data.** { *; }

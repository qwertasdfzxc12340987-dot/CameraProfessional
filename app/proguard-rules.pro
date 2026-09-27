# Add project specific ProGuard rules here.
# ML Kit models are loaded dynamically at runtime - keep their public API.
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# sherpa-onnx is called from native code (JNI) by exact class, field and method names; renaming or removing any of them
# makes the app abort the moment speech starts.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# The native side calls invoke([F)Ljava/lang/Integer; on the audio callback by signature, so the callback classes
# (anonymous objects implementing (FloatArray) -> Int in KokoroSpeaker) must keep their invoke methods.
-keepclassmembers class * implements kotlin.jvm.functions.Function1 {
    public java.lang.Object invoke(java.lang.Object);
    public java.lang.Integer invoke(float[]);
}
-keep class com.readaloud.tts.KokoroSpeaker$* { *; }

# PDFBox-Android and its optional dependencies reference classes that are not on Android
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
-dontwarn javax.xml.stream.**
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.harmony.** { *; }

# Apache Commons Compress optional codecs
-dontwarn org.apache.commons.compress.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.tukaani.xz.**
-dontwarn com.github.benmanes.caffeine.**
-dontwarn org.objectweb.asm.**

# Yerel koddan (whisper_jni.cpp) adıyla ve yapıcısıyla oluşturulan sınıf.
-keep class com.alterra.canliceviri.asr.NativeTranscript { *; }
# JNI fonksiyon adları sınıf adını içeriyor; yerel metodu olan sınıfların adı değişmesin.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

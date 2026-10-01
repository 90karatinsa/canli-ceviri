# Yerel koddan (hymt_jni.cpp) adıyla ve yapıcısıyla oluşturulan sınıf.
-keep class com.alterra.canliceviri.llm.LlmResult { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

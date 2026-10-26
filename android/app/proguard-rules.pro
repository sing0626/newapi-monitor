# NewAPI Monitor — release 用 proguard 規則（目前 isMinifyEnabled=false，僅留檔備用）
# 保留 kotlinx.serialization 生成嘅 serializer
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.newapimonitor.data.store.** {
    *** Companion;
}
-keepclasseswithmembers class com.newapimonitor.data.store.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn okhttp3.internal.platform.**

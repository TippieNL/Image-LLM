# Unfiltered AI Studio — R8 rules.
# Most libraries (Retrofit 3, OkHttp 5, kotlinx.serialization, Room, Hilt, Coil, WorkManager) ship
# their own consumer rules; these cover what is specific to this app.

-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, EnclosingMethod

# kotlinx.serialization: keep generated serializers for app models (polymorphic ProviderSettings is
# looked up by its serial name at runtime).
-keepclassmembers @kotlinx.serialization.Serializable class com.unfiltered.studio.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.unfiltered.studio.**$$serializer { *; }
-keepclassmembers class com.unfiltered.studio.** {
    *** Companion;
}

# Retrofit service interfaces (generic return types of suspend functions must survive shrinking).
-keep,allowobfuscation interface com.unfiltered.studio.data.network.huggingface.HfHubApi
-keep,allowobfuscation interface com.unfiltered.studio.data.network.civitai.CivitaiApi
-keep,allowobfuscation interface com.unfiltered.studio.data.providers.a1111.A1111Api
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# OkHttp optional TLS providers.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

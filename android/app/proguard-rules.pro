# Keep SQLCipher native bindings.
-keep class net.sqlcipher.** { *; }
-keep class net.zetetic.** { *; }

# Retrofit interfaces are referenced reflectively.
-keep interface cl.guardiania.agent.data.GuardianApi { *; }
-keepattributes Signature, Annotation, InnerClasses, *Annotation*

# Kotlin serialization: keep generated serializers and companions.
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class cl.guardiania.agent.**$$serializer { *; }
-keepclassmembers class cl.guardiania.agent.** { *** Companion; }
-keepclasseswithmembers class cl.guardiania.agent.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn kotlinx.serialization.**

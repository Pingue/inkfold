# kotlinx.serialization keeps generated serializers via @Serializable.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class uk.co.mfrost.inkfold.model.** {
    *** Companion;
}
-keepclasseswithmembers class uk.co.mfrost.inkfold.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

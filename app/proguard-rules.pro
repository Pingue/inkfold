# kotlinx.serialization keeps generated serializers via @Serializable.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class app.inkfold.model.** {
    *** Companion;
}
-keepclasseswithmembers class app.inkfold.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# kotlinx.serialization keeps generated serializers via @Serializable.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class uk.co.mfrost.inkfold.model.** {
    *** Companion;
}
-keepclasseswithmembers class uk.co.mfrost.inkfold.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# WorkManager's Room database is instantiated reflectively; R8 full mode
# strips the generated no-arg constructor without this, crashing at startup.
-keep class * extends androidx.room.RoomDatabase {
    <init>();
}

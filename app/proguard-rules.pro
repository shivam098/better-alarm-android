# kotlinx.serialization generates a companion `serializer()` for every
# @Serializable class. R8 cannot see it is used, so without these rules a
# release build would fail at runtime with SerializationException -- and the
# thing that would break is reading your saved alarms.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class com.alarmy.** {
    *** Companion;
}
-keepclasseswithmembers class com.alarmy.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.alarmy.**$$serializer { *; }

# Enum entries are matched by name in the JSON, so obfuscating them would
# silently invalidate every stored alarm on upgrade.
-keepclassmembers enum com.alarmy.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Receivers and services are constructed by the system from their manifest
# names, so they must keep their identity.
-keep class com.alarmy.app.alarm.AlarmReceiver { *; }
-keep class com.alarmy.app.alarm.BootReceiver { *; }
-keep class com.alarmy.app.alarm.AlarmActionReceiver { *; }
-keep class com.alarmy.app.alarm.RingingService { *; }
-keep class com.alarmy.app.BetterAlarmApplication { *; }

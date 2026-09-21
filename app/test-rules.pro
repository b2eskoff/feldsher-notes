# Error Prone compiler-only annotation references a javac enum, never used by Android tests.
-dontwarn javax.lang.model.element.Modifier
# Instrumentation selects these entrypoints by name, including against the old APK.
-keep class ru.ainur.feldshernotes.UpgradeOnDeviceTest { *; }
-keep class ru.ainur.feldshernotes.AudioServiceOnDeviceTest { *; }

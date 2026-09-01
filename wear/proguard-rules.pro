# Wear app R8 rules.
# kotlinx.serialization keeps its generated serializers via @Serializable; the plugin emits the
# required rules, so nothing extra is needed for the Data Layer message contract yet.
-dontwarn org.slf4j.**

# Line numbers survive shrinking for the same reason as in app/proguard-rules.pro: a watch crash
# from a distributed build has to be readable against the source with mapping.txt applied.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

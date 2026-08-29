# Wear app R8 rules.
# kotlinx.serialization keeps its generated serializers via @Serializable; the plugin emits the
# required rules, so nothing extra is needed for the Data Layer message contract yet.
-dontwarn org.slf4j.**

# Add project specific ProGuard rules here.
# By default, the active rules are defined in the Android SDK's default proguard-android-optimize.txt
# and Compose compiler's own built-in rules.

# For serializable keys in Navigation3 (using kotlinx.serialization):
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}

# Add project specific ProGuard rules here.
# By default, the active rules are defined in the Android SDK's default proguard-android-optimize.txt
# and Compose compiler's own built-in rules.

# For serializable keys in Navigation3 (using kotlinx.serialization):
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}

# Jetpack XR's scenecore/androidxr split-engine artifacts reference
# com.android.extensions.xr.* platform-extension classes that only exist on
# Android XR system images (SM-I610 etc). They are absent from every AAR in
# the compile classpath by design - the platform, not an app dependency,
# supplies them at runtime on XR hardware. R8 flags them as "missing" during
# release minification; these are the exact -dontwarn rules R8 generates
# into app/build/outputs/mapping/release/missing_rules.txt for this project.
-dontwarn com.android.extensions.xr.XrExtensionResult
-dontwarn com.android.extensions.xr.XrExtensions
-dontwarn com.android.extensions.xr.function.Consumer
-dontwarn com.android.extensions.xr.node.InputEvent$HitInfo
-dontwarn com.android.extensions.xr.node.InputEvent
-dontwarn com.android.extensions.xr.node.Mat4f
-dontwarn com.android.extensions.xr.node.Node
-dontwarn com.android.extensions.xr.node.NodeTransaction
-dontwarn com.android.extensions.xr.node.NodeTransform
-dontwarn com.android.extensions.xr.node.Vec3
-dontwarn com.android.extensions.xr.splitengine.BufferHandle
-dontwarn com.android.extensions.xr.splitengine.MessageGroupCallback
-dontwarn com.android.extensions.xr.splitengine.RequestCallback
-dontwarn com.android.extensions.xr.splitengine.SystemRendererConnection
-dontwarn com.android.extensions.xr.subspace.Subspace

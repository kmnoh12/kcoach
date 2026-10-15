# MediaPipe 0.10.35 does not ship consumer rules for native-to-Java lookups.
# JNI resolves framework classes, Packet.create(), callback methods and
# ProtoUtil.SerializedMessage.typeName/value by their original names.
-keep class com.google.mediapipe.framework.MediaPipeException { *; }
-keep class com.google.mediapipe.framework.Packet { *; }
-keep class com.google.mediapipe.framework.PacketCreator { *; }
-keep class com.google.mediapipe.framework.PacketListCallback { *; }
-keep class com.google.mediapipe.framework.PacketCallback { *; }
-keep class com.google.mediapipe.framework.ProtoUtil$SerializedMessage { *; }

# Generated message schemas refer to their fields reflectively at task startup.
# Preserve MediaPipe protobuf fields without disabling shrinking for the app.
-keepclassmembers class com.google.mediapipe.** extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

# Graph initializes FluentLogger.forEnclosingClass(). Flogger finds its caller
# from real stack frames: R8 inlining/merging breaks that lookup before the pose
# task starts (ExceptionInInitializerError: no caller found on the stack).
# Keep this small logging dependency; the rest of the app still uses shrinking.
# https://github.com/google-ai-edge/mediapipe/issues/6138
-keep class com.google.common.flogger.** { *; }

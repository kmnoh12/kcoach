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
# protobuf-javalite 4.26.1 ships no R8 rules, so this covers every message, not
# only MediaPipe's: CalculatorGraphConfig/Node hold google.protobuf.Any, and R8
# reduced an unprotected Any to an empty abstract class (0.7.7 POSE_INIT:
# "Can't instantiate abstract class com.google.protobuf.d").
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

# Graph initializes FluentLogger.forEnclosingClass(). Flogger finds its caller
# from real stack frames: R8 inlining/merging breaks that lookup before the pose
# task starts (ExceptionInInitializerError: no caller found on the stack).
# Keep this small logging dependency; the rest of the app still uses shrinking.
# https://github.com/google-ai-edge/mediapipe/issues/6138
-keep class com.google.common.flogger.** { *; }

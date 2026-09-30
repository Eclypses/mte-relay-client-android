# Consumer ProGuard/R8 rules — automatically applied to any app that depends on
# this library and enables minification (Flutter enables R8 for release builds by
# default, so this is the common case).
#
# Nothing in the relay's own code needs keeping. The pairing path used to reach the
# MTE pair entirely by reflection, and R8 rewrites a Class.forName literal but not the
# getDeclaredField("pairId") string beside it, so every minified release died on
# its first request with NoSuchFieldException until the two classes were kept.
# Frame v2 constructs the pair directly (session/MtePair.kt).
#
# The MTE core is the first exception. libmtejni.so reads MteBase's fields BY NAME
# over JNI, so R8 renaming them aborts the process natively on the first MTE call:
#   JNI FatalError called: MteBase.init() failed to get myEntropyInput
# mte-client-android keeps its own package, but only from 2026-08-10 -- after 4.2.1
# was published -- so the 4.2.1 on Maven Central ships no rule and every minified
# consumer of it crashes. Found by blind run 9. The earlier "verified" release builds
# resolved the MTE core from a sibling source checkout, which had the rule, and so
# never saw it. Kept here so a Relay customer is safe whatever MTE core resolves.
-keep class com.eclypses.mte.** { *; }

# Logging is the second exception.
#
# logback instantiates its appenders reflectively from assets/logback.xml, so R8
# sees no reference to them and strips them. The same verified build threw
#   ClassNotFoundException: ch.qos.logback.core.FileAppender
#   ClassNotFoundException: ch.qos.logback.classic.android.LogcatAppender
# and produced no MTE log output at all. That breaks this library's own
# troubleshooting advice exactly where it is needed -- release is the only build
# that minifies, and often the only build a customer can reproduce a problem in --
# and it silently disables enableFileLogging()/readLogFile(), which are public API.
-keep class ch.qos.logback.** { *; }
-keep class org.slf4j.** { *; }
-dontwarn ch.qos.logback.**
-dontwarn org.slf4j.**

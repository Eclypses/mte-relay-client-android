# Consumer ProGuard/R8 rules — automatically applied to any app that depends on
# this library and enables minification (Flutter enables R8 for release builds by
# default, so this is the common case).
#
# The control-plane pairing path (OkHttpRelayControlPlaneClient) bridges to the
# legacy `com.eclypses.relay.Pair` (and its `DecodeResult`) ENTIRELY by reflection:
#   Class.forName("com.eclypses.relay.Pair")
#   pairClass.getDeclaredField("pairId" | "encMyPublicKey" | "decMyPublicKey" | …)
#   legacyPair.javaClass.getDeclaredMethod("encode" | "decode" | "finishEncrypt" | …)
#   decodeResult.javaClass.getDeclaredField("decodedBytes")
# R8 rewrites the Class.forName string literal to the obfuscated class name, but it
# does NOT rewrite the getDeclaredField("pairId") string. So under minification the
# class resolves (renamed, e.g. i8.a) while the field lookup fails at runtime:
#   NoSuchFieldException / "No field pairId in class Li8/a"
# on the very first relay request. Keep these classes and all their members intact
# so the reflective handshake survives obfuscation.
-keep class com.eclypses.relay.Pair { *; }
-keep class com.eclypses.relay.DecodeResult { *; }

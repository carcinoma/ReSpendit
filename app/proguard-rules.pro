# Nimbus JOSE: optional crypto providers that are not on the classpath.
-dontwarn com.google.crypto.tink.**
-dontwarn org.bouncycastle.**
-dontwarn net.jcip.annotations.**
-dontwarn com.google.errorprone.annotations.**
# Readable screen names in the diagnostic log.
-keepnames class local.cardmin.ui.Screen$*
-keepnames class local.cardmin.ui.TanPurpose$*

# Nimbus parses JWKs with a shaded Gson that relies on reflection.
-keep class com.nimbusds.** { *; }

# Scrigno R8 rules.

# The protocol libraries load classes by name (JSch algorithms, smbj/MBassador event handlers,
# Commons Net listing parsers, Bouncy Castle digests): keep them intact.
-keep class com.jcraft.jsch.** { *; }
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-keep class org.apache.commons.net.** { *; }
-keep class org.bouncycastle.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Optional integrations of those libraries that do not exist on Android.
-dontwarn javax.naming.**
-dontwarn javax.security.auth.kerberos.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.el.**
-dontwarn org.slf4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.newsclub.net.unix.**
-dontwarn com.sun.jna.**
-dontwarn org.bouncycastle.**
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-dontwarn sun.security.**
-dontwarn java.beans.**
-dontwarn com.sun.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

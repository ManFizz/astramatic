# SMB (smbj): события рассылаются через mbassador, обработчики ищутся рефлексией по аннотациям.
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# BouncyCastle — MD4 для входа по NTLM; алгоритмы находятся по именам классов.
-keep class org.bouncycastle.** { *; }

# Классы, которых нет на Android, — ими эти библиотеки пользуются только на обычной JVM.
-dontwarn org.bouncycastle.**
-dontwarn com.hierynomus.**
-dontwarn net.engio.mbassy.**
-dontwarn org.slf4j.**
-dontwarn javax.naming.**
-dontwarn javax.el.**
-dontwarn javax.security.auth.**
-dontwarn org.ietf.jgss.**

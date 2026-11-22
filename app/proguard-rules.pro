# El código propio de Solaris se deja entero (nombres y clases): R8 solo optimiza las librerías.
-keep class com.sebas.solaris.** { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, SourceFile, LineNumberTable

# OkHttp y sus dependencias opcionales
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

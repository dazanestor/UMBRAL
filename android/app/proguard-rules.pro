# Linphone usa JNI y reflexión: no ofuscar ni recortar su SDK.
-keep class org.linphone.** { *; }
-keepclassmembers class org.linphone.** { *; }
-dontwarn org.linphone.**
# Firebase/ZXing/OkHttp llevan sus propias reglas de consumidor.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

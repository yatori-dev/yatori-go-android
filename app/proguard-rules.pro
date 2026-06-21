# ===== Yatori-Android ProGuard Rules =====

# --- Hilt / Dagger ---
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class *
-keep @dagger.hilt.android.AndroidEntryPoint class *
-keep @dagger.hilt.InstallIn class *
-keepnames @dagger.hilt.android.lifecycle.HiltViewModel class *

# --- Room ---
-keep class com.yatori.android.data.local.entity.** { *; }
-keep class com.yatori.android.data.local.dao.** { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase { *; }

# --- Gson / domain models ---
-keep class com.yatori.android.domain.model.** { *; }
-keepattributes Signature,*Annotation*
-dontwarn com.google.gson.**

# --- Retrofit / OkHttp ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# --- Coroutines ---
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# --- Compose ---
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# --- WorkManager / Hilt-Work ---
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-dontwarn androidx.work.**

# --- DataStore ---
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { *; }
-dontwarn com.google.protobuf.**

# --- ONNX Runtime ---
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# --- JavaMail (javax.mail) ---
-keep class javax.mail.** { *; }
-keep class com.sun.mail.** { *; }
-dontwarn javax.mail.**
-dontwarn com.sun.mail.**

# --- JSoup ---
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# --- BouncyCastle ---
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# --- Security guard (keep for runtime reflection) ---
-keep class com.yatori.android.security.** { *; }

# --- Navigation ---
-keepnames class * extends androidx.navigation.NavArgs

# --- Lifecycle ---
-keepnames class * extends androidx.lifecycle.ViewModel
-keepclassmembers class * extends androidx.lifecycle.ViewModel { <init>(...); }

# --- General ---
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

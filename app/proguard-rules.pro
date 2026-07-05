# ============================================================================
# ProGuard / R8 规则配置
# 项目：HeartRateMonitorMobile
# 目标：开启 isMinifyEnabled + isShrinkResources 后保证运行时稳定
# ============================================================================

# ----------------------------------------------------------------------------
# 1. 通用：保留调试堆栈信息（崩溃日志可读）
# ----------------------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes Exceptions,Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations,RuntimeInvisibleParameterAnnotations
-keepattributes AnnotationDefault

# ----------------------------------------------------------------------------
# 2. Kotlin 元数据保留（Kotlin 反射 / 协程内部依赖）
# ----------------------------------------------------------------------------
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$$serializer { *; }
-keepclassmembers class kotlin.** { *; }
-dontwarn kotlin.**

# Kotlin 协程
-keep class kotlinx.coroutines.** { *; }
-keepclassmembers class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# ----------------------------------------------------------------------------
# 3. Room 数据库（关键：实体字段名被生成的 _Impl 类按名访问）
#    包路径：com.example.heart_rate_monitor_mobile.data.db
# ----------------------------------------------------------------------------
-keep class com.example.heart_rate_monitor_mobile.data.db.** { *; }
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keepclassmembers class * {
    @androidx.room.* <fields>;
    @androidx.room.* <methods>;
}
-dontwarn androidx.room.paging.**

# ----------------------------------------------------------------------------
# 4. MPAndroidChart（图表库，反射访问部分内部成员）
# ----------------------------------------------------------------------------
-keep class com.github.mikephil.charting.** { *; }
-keep class com.github.mikephil.charting.data.** { *; }
-keep class com.github.mikephil.charting.components.** { *; }
-keepclassmembers class com.github.mikephil.charting.** {
    public *;
    protected *;
}
-dontwarn com.github.mikephil.charting.**

# ----------------------------------------------------------------------------
# 5. NanoHTTPD（内置 HTTP 服务器）
# ----------------------------------------------------------------------------
-keep class org.nanohttpd.** { *; }
-keep class fi.iki.elonen.** { *; }
-keepclassmembers class org.nanohttpd.** { *; }
-keepclassmembers class fi.iki.elonen.** { *; }
-dontwarn org.nanohttpd.**
-dontwarn fi.iki.elonen.**

# ----------------------------------------------------------------------------
# 6. Kable（蓝牙 LE 库）
# ----------------------------------------------------------------------------
-keep class com.juul.kable.** { *; }
-keepclassmembers class com.juul.kable.** { *; }
-dontwarn com.juul.kable.**

# ----------------------------------------------------------------------------
# 7. ColorPickerView（颜色选择器）
# ----------------------------------------------------------------------------
-keep class com.skydoves.colorpickerview.** { *; }
-keepclassmembers class com.skydoves.colorpickerview.** { *; }
-dontwarn com.skydoves.colorpickerview.**

# ----------------------------------------------------------------------------
# 8. PermissionX（权限请求库）
# ----------------------------------------------------------------------------
-keep class com.permissionx.** { *; }
-keep class com.guolindev.permissionx.** { *; }
-keepclassmembers class com.permissionx.** { *; }
-keepclassmembers class com.guolindev.permissionx.** { *; }
-dontwarn com.permissionx.**
-dontwarn com.guolindev.permissionx.**

# ----------------------------------------------------------------------------
# 9. 项目数据类：Webhook / WebhookTrigger
#    原因：使用 org.json.JSONObject put/get 显式按字段名访问，
#          虽非反射，但保留字段名更稳妥，避免与 SharedPreferences 中
#          已存的 JSON 字符串不匹配。
# ----------------------------------------------------------------------------
-keep class com.example.heart_rate_monitor_mobile.data.Webhook { *; }
-keep class com.example.heart_rate_monitor_mobile.data.WebhookTrigger { *; }
-keepclassmembers class com.example.heart_rate_monitor_mobile.data.Webhook {
    <fields>;
}
-keepclassmembers enum com.example.heart_rate_monitor_mobile.data.WebhookTrigger {
    *;
}

# ----------------------------------------------------------------------------
# 10. AndroidX / Material / Compose 通用（多数自带 consumer rules，
#     这里补充保险规则）
# ----------------------------------------------------------------------------
-keep class androidx.** { *; }
-keep class com.google.android.material.** { *; }
-keepclassmembers class androidx.** { *; }
-keepclassmembers class com.google.android.material.** { *; }
-dontwarn androidx.**
-dontwarn com.google.android.material.**

# ViewBinding（自动生成，但保险保留）
-keep class com.example.heart_rate_monitor_mobile.databinding.** { *; }

# ----------------------------------------------------------------------------
# 11. 服务 / 广播 / Activity 入口（Manifest 声明的组件，
#     R8 通常自动保留，这里显式确保）
# ----------------------------------------------------------------------------
-keep class com.example.heart_rate_monitor_mobile.MainActivity { *; }
-keep class com.example.heart_rate_monitor_mobile.ui.** { *; }
-keep class com.example.heart_rate_monitor_mobile.service.** { *; }
-keep class com.example.heart_rate_monitor_mobile.data.** { *; }

# ----------------------------------------------------------------------------
# 12. JNI / Native 调用（如有）
# ----------------------------------------------------------------------------
-keepclasseswithmembernames class * {
    native <methods>;
}

# ----------------------------------------------------------------------------
# 13. WebView JS 接口（项目暂未使用，保留模板）
# ----------------------------------------------------------------------------
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#    public *;
#}

# ----------------------------------------------------------------------------
# 14. 枚举通用保留
# ----------------------------------------------------------------------------
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ----------------------------------------------------------------------------
# 15. Parcelable / Serializable（Intent 传递）
# ----------------------------------------------------------------------------
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ----------------------------------------------------------------------------
# 16. R 文件（资源 ID 引用）
# ----------------------------------------------------------------------------
-keep class com.example.heart_rate_monitor_mobile.R { *; }
-keep class com.example.heart_rate_monitor_mobile.R$* { *; }

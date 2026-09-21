# ====================================================================
# ScannerLite - 自定义 ProGuard / R8 混淆规则
# ====================================================================

# 保留 ML Kit 关键类（防止模型加载或反射被混淆删除）
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }

# 保留 CameraX
-keep class androidx.camera.** { *; }

# 保留 Compose 相关注解和元数据
-keepclassmembers class * {
    @androidx.compose.runtime.Composable *;
}
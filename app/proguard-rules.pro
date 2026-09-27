# ProGuard / R8 混淆规则（正式版 release 构建启用）
# 当前 debug 构建 minifyEnabled=false，本文件不生效；发布前在 app/build.gradle.kts 的
# buildTypes.release 里打开 minifyEnabled=true 并指向本文件即可。

# ---------- Room（实体/DAO/数据库） ----------
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
}
# TypeConverter 通过反射实例化，必须保留
-keep class * implements androidx.room.TypeConverter { *; }
-dontwarn androidx.room.paging.**

# ---------- SQLCipher（JNI + 反射） ----------
-keep class net.zetetic.database.** { *; }
-keep class net.zetetic.sqlcipher.** { *; }
-keepclasseswithmembers,includedescriptorclasses class * {
    native <methods>;
}

# ---------- kotlinx.coroutines ----------
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ---------- Compose ----------
# 官方文档建议：为 @Composable 函数保留签名以稳定序列化，避免某些场景的崩溃
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
# Compose 编译器产物与运行时同名符号的冲突告警
-dontwarn androidx.compose.**

# ---------- 保留行号与源文件名，便于线上崩溃定位 ----------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 只有 release 开了 isMinifyEnabled=true 时这些规则才生效
# 入口类是靠 assets/xposed_init 里的字符串反射加载的，R8 看不见引用，必须 keep
-keep class com.jmcomic.hook.MainHook
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage
-dontwarn de.robv.android.xposed.**

# DexKit：DexKitBridge 的 native 方法在 C++ 侧按类/方法名注册，混淆改名会直接断，
# 整包 keep（含 FlatBuffers 编解码依赖）
-keep class org.luckypray.dexkit.** { *; }
-keep class com.google.flatbuffers.** { *; }
-dontwarn org.luckypray.dexkit.**

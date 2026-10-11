package com.jmcomic.hook

import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * 设置面板 <-> 宿主进程 的桥：libxposed RemotePreferences（框架核心存储）。
 *
 * 写入方：面板进程（SettingsActivity）经 XposedService.getRemotePreferences → 框架核心。
 * 读取方：宿主进程的模块实例经 XposedInterface.getRemotePreferences → 同一核心存储。
 *   （注意：LSPatch manager 模式下框架不向宿主进程投递 service binder——
 *     LSPApplication 只在 !useManager 时 Delivered XposedService；因此宿主读取
 *     必须走 XposedInterface 内置通道，service 监听仅作兜底快照。）
 * 读不到（老框架/未启用设置/异常）时全部默认开——与 v1.2.3 行为一致，绝不因读不到而关功能。
 */
object Config {

    const val GROUP = "jm_settings"

    const val K_AD_BLOCK = "ad_block"          // 网络层 + fetch 短路 + 覆盖层自动跳
    const val K_T2S = "t2s"                    // 繁→简注入
    const val K_AUTO_CHECKIN = "auto_checkin"  // 每日自动签到
    const val K_AUTO_MAKEUP = "auto_makeup"    //   └ 补签（消耗官方每月额度）
    const val K_AUTO_SKIP = "auto_skip"        //   └ 选线页自动选线 + 开屏链自动跳

    /** 宿主进程侧：框架接口（onModuleLoaded/onHotReloaded 传入）。 */
    @Volatile
    private var sApi: XposedInterface? = null

    /** 框架核心 prefs 缓存（null = 尚未取到，走默认值）。 */
    @Volatile
    private var sPrefs: SharedPreferences? = null

    @Volatile
    private var loggedRead = false

    private val defaults = mapOf(
        K_AD_BLOCK to true,
        K_T2S to true,
        K_AUTO_CHECKIN to true,
        K_AUTO_MAKEUP to true,
        K_AUTO_SKIP to true,
    )

    /** 宿主进程调用；幂等。优先框架内置通道，service 监听作兜底。 */
    fun initInHost(api: XposedInterface) {
        if (sApi === api && sPrefs != null) return
        sApi = api
        try {
            sPrefs = api.getRemotePreferences(GROUP)
        } catch (t: Throwable) {
            MainHook.log(MainHook.TAG + " 框架内置 prefs 不可用（走默认值/等待 service）: " + t)
        }
        if (sPrefs != null) return
        // 兜底：部分框架经 service 投递（LSPosed root 模式）
        try {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    try {
                        val p = service.getRemotePreferences(GROUP)
                        p.registerOnSharedPreferenceChangeListener { _, _ -> sPrefs = p }
                        sPrefs = p
                    } catch (t: Throwable) {
                        MainHook.log(MainHook.TAG + " RemotePreferences 获取失败: " + t)
                    }
                }

                override fun onServiceDied(service: XposedService) {
                    sPrefs = null
                }
            })
        } catch (t: Throwable) {
            MainHook.log(MainHook.TAG + " service listener 注册失败: " + t)
        }
    }

    fun enabled(key: String): Boolean {
        var p = sPrefs
        if (p == null) {
            // 迟到初始化（早期注入时框架未就绪）：再试一次内置通道
            val api = sApi
            if (api != null) {
                try {
                    p = api.getRemotePreferences(GROUP)
                    sPrefs = p
                } catch (ignore: Throwable) {
                }
            }
        }
        if (p == null) {
            MainHook.log(MainHook.TAG + " prefs 未就绪，key=" + key + " 走默认 " + (defaults[key] ?: true))
            return defaults[key] ?: true
        }
        return try {
            val v = p.getBoolean(key, defaults[key] ?: true)
            if (!loggedRead) {
                loggedRead = true
                val all = try { p.all.entries.joinToString(",") { it.key + "=" + it.value } } catch (ignore: Throwable) { "?" }
                MainHook.log(MainHook.TAG + " prefs 首读: key=" + key + " -> " + v + " | 全量: " + all)
            }
            v
        } catch (t: Throwable) {
            MainHook.log(MainHook.TAG + " prefs 读取异常 key=" + key + ": " + t)
            defaults[key] ?: true
        }
    }
}

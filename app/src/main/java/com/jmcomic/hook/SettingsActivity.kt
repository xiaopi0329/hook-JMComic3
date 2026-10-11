package com.jmcomic.hook

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 模块设置面板（宿主面板）：LSPosed / LSPatch 管理器点模块条目"设置"打开。
 * 开关经 libxposed RemotePreferences 写入框架数据库，宿主进程侧 Config 实时读取。
 *
 * 纯代码布局（无 XML/res 依赖）：这个模块 APK 只有这一个界面，写死最省。
 * 面板进程拿不到 service（框架不支持/未送达）时禁用全部开关并提示，绝不假装可改。
 */
class SettingsActivity : Activity() {

    private var service: XposedService? = null

    private data class Row(val key: String, val title: String, val desc: String)

    private val rows = listOf(
        Row(Config.K_AD_BLOCK, "去广告", "拦截广告接口 + fetch 短路 + 强制 ad_free（重启宿主生效于新页面）"),
        Row(Config.K_T2S, "繁体转简体", "页面文本与常用属性实时转换（重启宿主生效于新页面）"),
        Row(Config.K_AUTO_CHECKIN, "每日自动签到", "官方免费接口：签到 + 全勤（每日首次启动宿主执行）"),
        Row(Config.K_AUTO_MAKEUP, "自动补签", "消耗官方每月补签额度（默认上限 5 次/月）"),
        Row(Config.K_AUTO_SKIP, "自动跳开屏", "选线页自动选线 + 自动点年龄确认/X，直达正文"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "hook-JMComic3 设置"

        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply {
            addView(root)
            setBackgroundColor(0xFF101418.toInt())
        })

        root.addView(TextView(this).apply {
            text = "开关写入 LSPosed 框架数据库，宿主进程实时读取；" +
                    "改动对已加载页面下次注入时生效。"
            setTextColor(0xFF9AA4AF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, 0, 0, dp(12))
        })

        for (row in rows) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(10), dp(4), dp(10))
            }
            line.addView(TextView(this).apply {
                text = row.title
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(typeface, Typeface.BOLD)
            })
            line.addView(TextView(this).apply {
                text = row.desc
                setTextColor(0xFF8A939E.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            })
            val sw = Switch(this).apply {
                id = View.generateViewId()
                isChecked = true
                isEnabled = false
                setOnCheckedChangeListener { _, checked -> save(row.key, checked) }
            }
            val wrap = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(line, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(sw)
            }
            root.addView(wrap)
            root.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                    topMargin = dp(4)
                }
                setBackgroundColor(0xFF2A303A.toInt())
            })
            switches[row.key] = sw
        }

        root.addView(Button(this).apply {
            text = "立即执行一次签到检查"
            setOnClickListener {
                Toast.makeText(this@SettingsActivity,
                    "签到在宿主启动时自动执行（每日一次）；想立刻跑：强开一次 JMComic3",
                    Toast.LENGTH_LONG).show()
            }
        })

        val status = TextView(this).apply {
            text = "正在连接 LSPosed 框架…"
            setTextColor(0xFF9AA4AF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(10), 0, 0)
        }
        root.addView(status)
        this.status = status

        // 连接框架 service（libxposed 101+）
        try {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(s: XposedService) {
                    service = s
                    runOnUiThread { bindUi(s) }
                }

                override fun onServiceDied(s: XposedService) {
                    service = null
                    runOnUiThread {
                        status.text = "框架连接已断开，开关暂不可用"
                        for (sw in rowSwitches()) sw.isEnabled = false
                    }
                }
            })
        } catch (t: Throwable) {
            status.text = "libxposed service 不可用: " + t
        }
    }

    private val switches = HashMap<String, Switch>()
    private var status: TextView? = null
    private fun rowSwitches(): List<Switch> = switches.values.toList()

    private fun bindUi(s: XposedService) {
        val prefs = try {
            s.getRemotePreferences(Config.GROUP)
        } catch (t: Throwable) {
            status?.text = "RemotePreferences 不可用: " + t
            return
        }
        for (r in rows) {
            switches[r.key]?.apply {
                isChecked = prefs.getBoolean(r.key, true)
                isEnabled = true
            }
        }
        val st = try {
            val sp = createPackageContext(MainHook.TARGET_PKG, 0)
                .getSharedPreferences("jm_autocheckin", MODE_PRIVATE)
            val d = sp.getString("run_date", null)
            if (d != null) "上次签到：$d（成功=" + sp.getBoolean("run_ok", false) + "）" else "宿主内今天还没跑过签到"
        } catch (t: Throwable) {
            "读不到宿主签到状态（正常：未装宿主/无权限）"
        }
        status?.text = "已连接 " + s.frameworkName + " " + s.frameworkVersion + " · " + st +
                " · " + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
    }

    private fun save(key: String, value: Boolean) {
        val s = service ?: return
        try {
            val ok = s.getRemotePreferences(Config.GROUP).edit().putBoolean(key, value).commit()
            android.util.Log.i("hook-JMComic3", "面板写入 " + key + "=" + value + " commit=" + ok)
            Toast.makeText(this, if (ok) "已保存 " + key else "保存失败 commit=false", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Toast.makeText(this, "写入失败: " + t, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(v: Int): Int =
        (resources.displayMetrics.density * v + 0.5f).toInt()
}

package com.jmcomic.hook

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
 * 排版：深色底 + 圆角卡片分组（说明 / 功能开关 / 签到），开关右对齐、行内分隔线缩进。
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
        Row(Config.K_BOTTOM_GLASS, "iOS 液态玻璃底栏", "底栏改半透明毛玻璃（需开启去广告）"),
        Row(Config.K_TAB_SIMPLE, "精简底栏", "只保留 首页/分类/小电影/个人中心（需开启去广告）"),
        Row(Config.K_LOCK_BADGE, "未解锁标签", "需 Jcoin 解锁的漫画在封面上打「未解锁」角标"),
    )

    // ---------------------------------------------------------------- 调色板
    private val cBg = 0xFF0E1116.toInt()        // 页面底
    private val cCard = 0xFF171D26.toInt()      // 卡片底
    private val cLine = 0xFF242C38.toInt()      // 行分隔线
    private val cTitle = 0xFFF2F5F8.toInt()     // 行标题
    private val cDesc = 0xFF8A939E.toInt()      // 行描述 / 说明
    private val cSection = 0xFF6E7884.toInt()   // 分组小标题
    private val cAccent = 0xFF2D6CDF.toInt()    // 按钮底

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "hook-JMComic3 设置"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(20))
        }
        setContentView(ScrollView(this).apply {
            addView(root)
        })
        window.decorView.setBackgroundColor(cBg)

        // ── 说明卡 ───────────────────────────────────────────────────────
        root.addView(sectionLabel("说明"))
        root.addView(card().apply {
            addView(TextView(this@SettingsActivity).apply {
                text = "开关写入 LSPosed 框架数据库，宿主进程实时读取；" +
                        "改动对已加载页面下次注入时生效。"
                setTextColor(cDesc)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setLineSpacing(dp(2).toFloat(), 1f)
            })
        }, cardLp())

        // ── 功能开关卡 ───────────────────────────────────────────────────
        root.addView(sectionLabel("功能开关"))
        val settingsCard = card()
        rows.forEachIndexed { i, row ->
            if (i > 0) settingsCard.addView(insetDivider())
            settingsCard.addView(switchRow(row))
        }
        root.addView(settingsCard, cardLp())

        // ── 签到卡 ───────────────────────────────────────────────────────
        root.addView(sectionLabel("签到"))
        val checkinCard = card()
        checkinCard.addView(Button(this).apply {
            text = "立即执行一次签到检查"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = GradientDrawable().apply {
                setColor(cAccent)
                cornerRadius = dp(10).toFloat()
            }
            setOnClickListener {
                Toast.makeText(this@SettingsActivity,
                    "签到在宿主启动时自动执行（每日一次）；想立刻跑：强开一次 JMComic3",
                    Toast.LENGTH_LONG).show()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply {
            setMargins(dp(12), dp(12), dp(12), 0)
        })

        val status = TextView(this).apply {
            text = "正在连接 LSPosed 框架…"
            setTextColor(cDesc)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(12), dp(10), dp(12), dp(4))
        }
        checkinCard.addView(status)
        root.addView(checkinCard, cardLp())
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

    // ---------------------------------------------------------------- 开关行

    /**
     * 一行开关：左侧标题+描述（weight=1），右侧 Switch 垂直居中。
     * Switch 登记进 switches 供 bindUi/rowSwitches 使用（与旧版一致）。
     */
    private fun switchRow(row: Row): View {
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        textCol.addView(TextView(this).apply {
            text = row.title
            setTextColor(cTitle)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
        })
        textCol.addView(TextView(this).apply {
            text = row.desc
            setTextColor(cDesc)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(dp(1).toFloat(), 1f)
            setPadding(0, dp(2), 0, 0)
        })

        val sw = Switch(this).apply {
            id = View.generateViewId()
            isChecked = true
            isEnabled = false
            setOnCheckedChangeListener { _, checked -> save(row.key, checked) }
        }
        switches[row.key] = sw

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(12))
            addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(sw)
        }
    }

    // ---------------------------------------------------------------- 卡片/杂项

    /** 圆角卡片容器（垂直 LinearLayout）。 */
    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(cCard)
            cornerRadius = dp(14).toFloat()
        }
    }

    /** 分组小标题（卡片上方）。 */
    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(cSection)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(6), dp(8), dp(6), dp(4))
    }

    /** 卡片的默认布局参数（与上方小标题构成一组节奏）。 */
    private fun cardLp(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    /** 卡片内行分隔线：左右缩进与行 padding 对齐，避免顶到圆角。 */
    private fun insetDivider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
            leftMargin = dp(16)
            rightMargin = dp(48)
        }
        setBackgroundColor(cLine)
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

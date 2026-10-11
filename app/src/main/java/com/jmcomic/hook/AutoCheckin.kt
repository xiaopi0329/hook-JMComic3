package com.jmcomic.hook

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.regex.Pattern
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import java.io.InputStream
import java.io.OutputStream

/**
 * 每日首次启动宿主时的"自动领取免费 JCoin"执行器（Kotlin）。
 *
 * 只调用官方【免费】接口，不触碰余额与付费解锁：
 *   1) daily             —— 读取签到面板（拿 daily_id / record / makeup_limit）
 *   2) daily_chk         —— 今日签到（若今天未签）
 *   3) daily_chk+date    —— 补签历史漏签日（消耗官方补签额度，见 auto_makeup 开关）
 *   4) daily_list/filter —— 当月全签后的全勤奖励
 *
 * 凭据来源：宿主 WebView localStorage（leveldb）——宿主进程与 WebView 同 UID，
 * 直接读 app_webview/Default/Local Storage/leveldb，取 memberInfo 的 uid/jwttoken 与 apiUrl。
 *
 * 请求/解密与宿主 HttpUtil 完全一致：
 *   Tokenparam = time,version ; Token = md5(time + "185Hcomic3PAPP7R")
 *   data = AES-256-ECB，key = ASCII(md5(time + seed))，seed 双候选
 */
object AutoCheckin {

    private const val TAG = MainHook.TAG
    private const val KEY_SEED = "185Hcomic3PAPP7R"
    private const val SEED_ALT = "18comicAPPContent"

    private const val PREFS = "jm_autocheckin"
    private const val K_DATE = "run_date"            // 今日是否已执行
    private const val K_OK = "run_ok"                // 上次执行是否成功
    private const val K_MAKEUP_USED = "makeup_used"  // 本机记录的补签次数
    private const val K_MAKEUP_MONTH = "makeup_month"

    @Volatile
    private var sStarted = false

    /** 热重载停机标志（旧代 MainHook.onHotReloading 置位；线程在尝试边界退出）。 */
    @Volatile
    private var sStopping = false

    /** 签到线程引用（仅停机时 interrupt 用）。 */
    @Volatile
    private var sThread: Thread? = null

    /** 每进程只调度一次；延迟若干秒等宿主完成启动/登录态载入，凭据没就绪时退避重试。 */
    @JvmStatic
    fun schedule(ctx: Context) {
        if (!Config.enabled(Config.K_AUTO_CHECKIN)) return
        if (sStarted) return
        sStarted = true
        val t = Thread({
            var attempt = 0
            while (attempt < 4 && !sStopping) {
                try {
                    Thread.sleep(if (attempt == 0) 8000L else 20000L)
                    if (sStopping) {
                        log("自动签到：收到停机请求，退出")
                        return@Thread
                    }
                    val o = run(ctx)
                    if (o != Outcome.NOT_LOGGED_IN) return@Thread // 成功/今天已办/接口失败不重试
                } catch (th: Throwable) {
                    if (sStopping) {
                        log("自动签到：停机打断，退出")
                        return@Thread
                    }
                    log("自动签到异常: " + th)
                }
                attempt++
            }
            if (!sStopping) log("自动签到放弃（未取得登录凭据）")
        }, "jm-autocheckin")
        sThread = t
        t.start()
    }

    /**
     * 热重载停机：置标志并打断睡眠，返回线程句柄供旧代有界 join。
     * 旧代必须等这个线程结束，旧 classloader 才可能被回收（API 102 热重载契约）。
     */
    @JvmStatic
    fun stopForHotReload(): Thread? {
        sStopping = true
        val t = sThread
        t?.interrupt()
        return t
    }

    private enum class Outcome { DONE, NOT_LOGGED_IN }

    private fun run(ctx: Context): Outcome {
        // ① 每日一次
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (today == sp.getString(K_DATE, null) && sp.getBoolean(K_OK, false)) {
            log("自动签到：今日（" + today + "）已执行过，跳过")
            return Outcome.DONE
        }

        // ② 凭据
        val c = readCreds(ctx)
        if (c.uid.isNullOrEmpty() || c.jwt.isNullOrEmpty()) {
            log("自动签到：未找到登录凭据（未登录？），稍后重试")
            return Outcome.NOT_LOGGED_IN
        }
        var api = c.apiUrl ?: discoverHost()
        if (api.isNullOrEmpty()) {
            log("自动签到：未取到接口域名，放弃")
            return Outcome.DONE
        }
        if (!api.endsWith("/")) api = api + "/"

        val version = hostVersion(ctx)
        val time = System.currentTimeMillis() / 1000L

        // ③ 拉签到面板
        val daily = getJson(api + "daily?user_id=" + enc(c.uid) + "&lang=TW", c, time, version)
        if (daily == null) {
            log("自动签到：daily 接口失败/解密失败，放弃本次")
            return Outcome.DONE
        }
        val dailyId = daily.optString("daily_id", "")
        val makeupLimit = daily.optInt("makeup_limit", 5)
        val cal = Calendar.getInstance()
        val todayDom = cal.get(Calendar.DATE)
        val month = cal.get(Calendar.MONTH) + 1
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

        // 解析 record：每天的 signed 三态（true 已签 / false 过期未签 / null 未来）
        val cells = ArrayList<IntArray>()   // [day, signedState] 0=false 1=true 2=null
        val record = daily.optJSONArray("record")
        var todaySigned = false
        var missedCount = 0
        if (record != null) {
            for (w in 0 until record.length()) {
                val week = record.optJSONArray(w) ?: continue
                for (d in 0 until week.length()) {
                    val cell = week.optJSONObject(d) ?: continue
                    val day = cell.optString("date", "0").toIntOrNull() ?: 0
                    if (day <= 0) continue
                    val signed = cell.opt("signed")
                    val state = if (signed == null || signed === JSONObject.NULL) 2
                        else if (signed == java.lang.Boolean.TRUE) 1 else 0
                    cells.add(intArrayOf(day, state))
                    if (day == todayDom && state == 1) todaySigned = true
                    if (state == 0 && day < todayDom) missedCount++
                }
            }
        }

        var acted = 0

        // ④ 今日签到
        when {
            todaySigned -> log("自动签到：今日（" + today + "）服务端已签到，无需重复")
            dailyId.isEmpty() -> log("自动签到：未取到 daily_id，跳过今日签到")
            else -> {
                val msg = postChk(api, c, time, version, c.uid!!, dailyId, null)
                log("自动签到：今日签到 -> " + msg)
                acted++
            }
        }

        // ⑤ 补签历史漏签（服务端 signed=false 且早于今天；受 auto_makeup 开关）
        if (Config.enabled(Config.K_AUTO_MAKEUP) && missedCount > 0) {
            val mkMonth = cal.get(Calendar.YEAR).toString() + "-" + month
            val used = if (mkMonth == sp.getString(K_MAKEUP_MONTH, null)) sp.getInt(K_MAKEUP_USED, 0) else 0
            val quota = Math.max(0, makeupLimit - used)
            if (quota <= 0) {
                log("自动签到：检测到 " + missedCount + " 个漏签日，但本月补签额度已用尽（"
                        + used + "/" + makeupLimit + "）")
            } else {
                var done = 0
                for (cell in cells) {
                    if (done >= quota) break
                    if (cell[1] == 0 && cell[0] < todayDom && cell[0] <= daysInMonth) {
                        val date = String.format(Locale.US, "%04d-%02d-%02d",
                            cal.get(Calendar.YEAR), month, cell[0])
                        val msg = postChk(api, c, time, version, c.uid!!, dailyId, date)
                        log("自动签到：补签 " + date + " -> " + msg)
                        if (msg != null && msg.contains("成功")) {
                            done++
                            acted++
                        }
                    }
                }
                sp.edit()
                    .putInt(K_MAKEUP_USED, used + done)
                    .putString(K_MAKEUP_MONTH, mkMonth)
                    .apply()
                log("自动签到：本次补签 " + done + " 天（本月已用 " + (used + done) + "/" + makeupLimit + "）")
            }
        } else if (!Config.enabled(Config.K_AUTO_MAKEUP) && missedCount > 0) {
            log("自动签到：检测到 " + missedCount + " 个可补签日（auto_makeup 已关闭，跳过）")
        }

        // ⑥ 全勤奖励（当月每天都已签才可领）
        var allSigned = cells.isNotEmpty()
        for (cell in cells) {
            if (cell[1] != 1) {
                allSigned = false
                break
            }
        }
        if (allSigned) {
            try {
                val body = "data=" + enc(month.toString())
                val r = postJson(api + "daily_list/filter?lang=TW", c, time, version, body)
                log("自动签到：全勤奖励 -> " + (r?.toString() ?: "(无可领取内容)"))
                acted++
            } catch (t: Throwable) {
                log("自动签到：全勤奖励异常 " + t)
            }
        }

        sp.edit().putString(K_DATE, today).putBoolean(K_OK, true).apply()
        log("自动签到：完成（今日 " + today + "，本次动作 " + acted + " 次）")
        return Outcome.DONE
    }

    // ------------------------------------------------------------------ 网络

    private fun getJson(url: String, c: Cred, time: Long, version: String): JSONObject? =
        try {
            decodeEnvelope(request("GET", url, null, c, time, version), time)
        } catch (t: Throwable) {
            log("请求失败 " + url + " : " + t)
            null
        }

    private fun postChk(api: String, c: Cred, time: Long, version: String,
                        uid: String, dailyId: String, date: String?): String? =
        try {
            val body = "user_id=" + enc(uid) + "&daily_id=" + enc(dailyId) +
                    (if (date != null) "&date=" + enc(date) else "")
            val r = postJson(api + "daily_chk?lang=TW", c, time, version, body)
            r?.optString("msg", r.toString()) ?: "(解密失败)"
        } catch (t: Throwable) {
            "(异常 " + t + ")"
        }

    private fun postJson(url: String, c: Cred, time: Long, version: String, body: String): JSONObject? =
        try {
            decodeEnvelope(request("POST", url, body, c, time, version), time)
        } catch (t: Throwable) {
            log("POST 失败 " + url + " : " + t)
            null
        }

    @Throws(Exception::class)
    private fun request(method: String, urlStr: String, body: String?, c: Cred, time: Long, version: String): String? {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("Tokenparam", time.toString() + "," + version)
            conn.setRequestProperty("Token", md5(time.toString() + KEY_SEED))
            if (!c.jwt.isNullOrEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + c.jwt)
            }
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) JMComic3/" + version)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                val b = body.toByteArray(StandardCharsets.UTF_8)
                conn.setFixedLengthStreamingMode(b.size)
                val os: OutputStream = conn.outputStream
                os.write(b)
                os.flush()
                os.close()
            }
            val code = conn.responseCode
            val input: InputStream = (if (code in 200..299) conn.inputStream else conn.errorStream) ?: return null
            val bo = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            var n: Int
            while (input.read(buf).also { n = it } > 0) bo.write(buf, 0, n)
            input.close()
            return bo.toString("UTF-8")
        } finally {
            try {
                conn.disconnect()
            } catch (ignore: Throwable) {
            }
        }
    }

    /** {code,data} -> 解密 data（AES-256-ECB，key=ASCII(md5(time+seed))），两个 seed 依次试。 */
    private fun decodeEnvelope(raw: String?, time: Long): JSONObject? {
        if (raw == null) return null
        try {
            val env = JSONObject(raw)
            if (env.optInt("code", -1) != 200) return null
            val data = env.opt("data")
            if (data is JSONObject) return data
            if (data !is String) return null
            for (seed in arrayOf(KEY_SEED, SEED_ALT)) {
                try {
                    val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
                    cipher.init(
                        Cipher.DECRYPT_MODE,
                        SecretKeySpec(md5(time.toString() + seed).toByteArray(StandardCharsets.UTF_8), "AES")
                    )
                    val out = cipher.doFinal(Base64.decode(data, Base64.DEFAULT))
                    val text = String(out, StandardCharsets.UTF_8).trim()
                    if (text.startsWith("{")) return JSONObject(text)
                    if (text.startsWith("[")) {
                        return JSONObject().put("list", JSONArray(text))
                    }
                } catch (ignore: Throwable) {
                }
            }
        } catch (ignore: Throwable) {
        }
        return null
    }

    /** 备用域名发现：解密 newsvr-2025.txt（与前端 FETCH_HOST 同算法）。 */
    private fun discoverHost(): String? {
        val urls = arrayOf(
            "https://rup4a04-c02.tos-cn-hongkong.bytepluses.com/newsvr-2025.txt",
            "https://rup4a04-c01.tos-ap-southeast-1.bytepluses.com/newsvr-2025.txt",
        )
        val key = md5("diosfjckwpqpdfjkvnqQjsik")
        for (u in urls) {
            try {
                val conn = URL(u).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                if (conn.responseCode / 100 != 2) continue
                val bo = ByteArrayOutputStream()
                val input = conn.inputStream
                val buf = ByteArray(4096)
                var n: Int
                while (input.read(buf).also { n = it } > 0) bo.write(buf, 0, n)
                input.close()
                val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "AES"))
                val text = String(
                    cipher.doFinal(Base64.decode(bo.toString("UTF-8").trim(), Base64.DEFAULT)),
                    StandardCharsets.UTF_8
                )
                val servers = JSONObject(text).optJSONArray("Server")
                if (servers != null && servers.length() > 0) {
                    return "https://" + servers.optString(0) + "/"
                }
            } catch (ignore: Throwable) {
            }
        }
        return null
    }

    // ------------------------------------------------------------------ 凭据

    private class Cred {
        var uid: String? = null
        var jwt: String? = null
        var apiUrl: String? = null
        fun jwtLen(): Int = jwt?.length ?: 0
    }

    /**
     * 从宿主 WebView 的 localStorage leveldb 里取 uid / jwttoken / apiUrl。
     * 编码事实：Chrome localStorage 含非 ASCII 的值存 UTF-16LE，纯 ASCII 值存 Latin-1。
     * memberInfo 是 UTF-16LE（含中文用户名/繁体文案），apiUrl 与独立的 jwttoken 键是 Latin-1。
     * 因此按编码各解一次、取匹配结果，而不是整块统一解码。
     */
    private fun readCreds(ctx: Context): Cred {
        val c = Cred()
        var dir: File? = null
        try {
            dir = File(ctx.dataDir, "app_webview/Default/Local Storage/leveldb")
        } catch (ignore: Throwable) {
        }
        if (dir == null || !dir.isDirectory) {
            val parent = ctx.filesDir?.parentFile
            val alt = parent?.let { File(it, "app_webview/Default/Local Storage/leveldb") }
            if (alt != null && alt.isDirectory) dir = alt
        }
        if (dir == null || !dir.isDirectory) {
            log("自动签到：找不到 localStorage 目录 " + dir + " (exists=" + (dir?.exists() == true) + ")")
            return c
        }
        val files = dir.listFiles()
        if (files == null) {
            log("自动签到：目录不可列出 " + dir)
            return c
        }
        log("自动签到：扫描 " + dir + " 下 " + files.size + " 个文件")
        // 新写入在 .log（后写），先扫 log 再扫 ldb，取最后一次出现的值
        val sorted = files.sortedWith(
            compareByDescending<File> { !it.name.endsWith(".log") }.thenBy { it.name }
        )
        for (f in sorted) {
            if (!f.isFile || f.length() > 8L * 1024 * 1024) continue
            val buf = try {
                readAll(f)
            } catch (t: Throwable) {
                continue
            }
            // memberInfo 的值是 UTF-16LE（含中文时），纯 ASCII 的键值则是 Latin-1；
            // 两种解码各试一遍，正则不会互相误匹配（UTF-16 里 'u' 后面跟着 0x00）。
            var uid = lastGroup(buf, "UTF-16LE", "\"uid\":\"(\\d{4,})\"")
            if (uid == null) uid = lastGroup(buf, "ISO-8859-1", "\"uid\":\"(\\d{4,})\"")
            if (uid != null) c.uid = uid
            // JWT 三段都可能含 + / = 或 - _（本 App 实测含 +），所以每段都用宽松字符集。
            val jwp = "[A-Za-z0-9_\\-+/=]{8}\\.[A-Za-z0-9_\\-+/=]{8}\\.[A-Za-z0-9_\\-+/=]{8}"
            var jwt = lastGroup(buf, "UTF-16LE", "\"jwttoken\":\"($jwp)\"")
            if (jwt == null) {
                // 独立的 jwttoken localStorage 键（纯 ASCII 值，Latin-1 存储，值带引号）
                jwt = lastGroup(buf, "ISO-8859-1", "jwttoken.{0,10}\"($jwp)\"")
            }
            if (jwt == null) {
                jwt = lastGroup(buf, "UTF-16LE", "\"jwttoken\":\"($jwp)")
            }
            if (jwt != null && jwt.length > c.jwtLen()) c.jwt = jwt
            val apiUrl = asciiValueAfterKey(buf, "apiUrl", "http")
            if (apiUrl != null) c.apiUrl = apiUrl
        }
        if (c.uid != null && c.jwt != null) {
            log("自动签到：取到凭据 uid=" + c.uid + " jwtLen=" + c.jwtLen() + " apiUrl=" + c.apiUrl)
        } else {
            log("自动签到：凭据不全 uid=" + c.uid + " jwtLen=" + c.jwtLen() + " apiUrl=" + c.apiUrl)
        }
        return c
    }

    /** 把整个缓冲区按指定编码解码后，取最后一个匹配的分组 1（leveldb 后写的在后面，天然取最新）。 */
    private fun lastGroup(buf: ByteArray, charset: String, regex: String): String? =
        try {
            val s = String(buf, java.nio.charset.Charset.forName(charset))
            val m = Pattern.compile(regex).matcher(s)
            var last: String? = null
            while (m.find()) last = m.group(1)
            last
        } catch (t: Throwable) {
            null
        }

    /**
     * ASCII 键（如 apiUrl / 独立的 jwttoken）后面紧跟 Latin-1 存的值。
     * 先在原始字节里定位键名，再向后找 valuePrefix（"http"）并读到分隔符。
     */
    private fun asciiValueAfterKey(buf: ByteArray, asciiKey: String, valuePrefix: String): String? {
        val key = asciiKey.toByteArray(StandardCharsets.US_ASCII)
        val at = indexOf(buf, key)
        if (at < 0) return null
        val end = Math.min(buf.size, at + 300)
        val prefix = valuePrefix.toByteArray(StandardCharsets.US_ASCII)
        var i = at + key.size
        while (i + prefix.size <= end) {
            var hit = true
            for (j in prefix.indices) {
                if (buf[i + j] != prefix[j]) {
                    hit = false
                    break
                }
            }
            if (hit) {
                val sb = StringBuilder()
                var p = i
                while (p < buf.size && sb.length < 256) {
                    val ch = buf[p].toInt() and 0xFF
                    if (ch <= 0x20 || ch == '"'.code || ch == '\\'.code || ch > 0x7E) break
                    sb.append(ch.toChar())
                    p++
                }
                val s = sb.toString()
                if (s.startsWith("http")) return s
            }
            i++
        }
        return null
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) {
                if (hay[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    @Throws(Exception::class)
    private fun readAll(f: File): ByteArray =
        FileInputStream(f).use { input ->
            val bo = ByteArrayOutputStream(Math.min(f.length(), 1L shl 20).toInt())
            val buf = ByteArray(8192)
            var n: Int
            while (input.read(buf).also { n = it } > 0) bo.write(buf, 0, n)
            bo.toByteArray()
        }

    // ------------------------------------------------------------------ 杂项

    private fun hostVersion(ctx: Context): String =
        try {
            ctx.packageManager.getPackageInfo(MainHook.TARGET_PKG, 0).versionName ?: "2.1.11"
        } catch (t: Throwable) {
            "2.1.11"
        }

    private fun md5(s: String): String =
        try {
            val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(StandardCharsets.UTF_8))
            val sb = StringBuilder(32)
            for (b in d) sb.append(String.format(Locale.ROOT, "%02x", b))
            sb.toString()
        } catch (t: Throwable) {
            ""
        }

    private fun enc(s: String?): String =
        try {
            URLEncoder.encode(s ?: "", "UTF-8")
        } catch (t: Throwable) {
            ""
        }

    private fun log(msg: String) {
        MainHook.log(TAG + " " + msg)
    }
}

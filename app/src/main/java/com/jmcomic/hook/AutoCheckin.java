package com.jmcomic.hook;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.util.Base64;

import de.robv.android.xposed.XposedBridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 每日首次启动宿主时的“自动领取免费 JCoin”执行器。
 *
 * 只调用官方【免费】接口，不触碰余额与付费解锁：
 *   1) daily           —— 读取签到面板（拿 daily_id / record / makeup_limit）
 *   2) daily_chk       —— 今日签到（若今天未签）
 *   3) daily_chk+date  —— 补签历史漏签日（消耗官方补签额度，见 auto_makeup 开关）
 *   4) daily_list/filter —— 当月全签后的全勤奖励
 *
 * 凭据来源：宿主自己的 WebView localStorage（leveldb）。宿主进程与 WebView 同 UID，
 * 直接读 /data/data/<pkg>/app_webview/Default/Local Storage/leveldb 即可，
 * 取 memberInfo 里的 uid / jwttoken 与 apiUrl。无需 JS 桥，时机上也不依赖页面加载。
 *
 * 请求/解密与宿主 HttpUtil 完全一致：
 *   Tokenparam = time,version ; Token = md5(time + "185Hcomic3PAPP7R")
 *   data       = AES-256-ECB，key = ASCII(md5(time + seed))，seed ∈ {185Hcomic3PAPP7R, 18comicAPPContent}
 */
final class AutoCheckin {

    private static final String TAG = "[hook-JMComic3]";
    private static final String KEY_SEED = "185Hcomic3PAPP7R";
    private static final String SEED_ALT = "18comicAPPContent";

    private static final String PREFS = "jm_autocheckin";
    private static final String K_DATE = "run_date";          // 今日是否已执行
    private static final String K_OK = "run_ok";              // 上次执行是否成功
    private static final String K_MAKEUP_USED = "makeup_used"; // 本机记录的补签次数
    private static final String K_MAKEUP_MONTH = "makeup_month";

    /** 是否自动补签（官方免费功能，但会消耗每月补签额度；关闭则只做当日签到+全勤） */
    private static final boolean AUTO_MAKEUP = true;

    private static volatile boolean sStarted = false;

    private AutoCheckin() {
    }

    /** 每进程只调度一次；延迟若干秒等宿主完成启动/登录态载入，凭据没就绪时退避重试。 */
    static void schedule(final Context ctx) {
        if (sStarted) {
            return;
        }
        sStarted = true;
        new Thread(() -> {
            for (int attempt = 0; attempt < 4; attempt++) {
                try {
                    Thread.sleep(attempt == 0 ? 8000L : 20000L);
                    Outcome o = run(ctx);
                    if (o == Outcome.NOT_LOGGED_IN) {
                        continue;   // 可能还没登录完成，稍后重试
                    }
                    return;         // 成功 / 今天已办过 / 凭据齐全但接口失败（不再重试，避免骚扰服务端）
                } catch (Throwable t) {
                    log("自动签到异常: " + t);
                }
            }
            log("自动签到放弃（未取得登录凭据）");
        }, "jm-autocheckin").start();
    }

    private enum Outcome { DONE, NOT_LOGGED_IN }

    private static Outcome run(Context ctx) {
        // ① 每日一次
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date());
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (today.equals(sp.getString(K_DATE, null)) && sp.getBoolean(K_OK, false)) {
            log("自动签到：今日（" + today + "）已执行过，跳过");
            return Outcome.DONE;
        }

        // ② 凭据
        Cred c = readCreds(ctx);
        if (c == null || c.uid == null || c.uid.isEmpty() || c.jwt == null || c.jwt.isEmpty()) {
            log("自动签到：未找到登录凭据（未登录？），稍后重试");
            return Outcome.NOT_LOGGED_IN;
        }
        String api = c.apiUrl != null ? c.apiUrl : discoverHost();
        if (api == null || api.isEmpty()) {
            log("自动签到：未取到接口域名，放弃");
            return Outcome.DONE;
        }
        if (!api.endsWith("/")) {
            api = api + "/";
        }

        String version = hostVersion(ctx);
        long time = System.currentTimeMillis() / 1000L;

        // ③ 拉签到面板
        JSONObject daily = getJson(api + "daily?user_id=" + enc(c.uid) + "&lang=TW", c, time, version);
        if (daily == null) {
            log("自动签到：daily 接口失败/解密失败，放弃本次");
            return Outcome.DONE;
        }
        String dailyId = daily.optString("daily_id", "");
        int makeupLimit = daily.optInt("makeup_limit", 5);
        Calendar cal = Calendar.getInstance();
        int todayDom = cal.get(Calendar.DATE);
        int month = cal.get(Calendar.MONTH) + 1;
        int daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH);

        // 解析 record：每天的 signed 三态（true 已签 / false 过期未签 / null 未来）
        List<int[]> cells = new ArrayList<>();   // [day, signedState] 0=false 1=true 2=null
        JSONArray record = daily.optJSONArray("record");
        boolean todaySigned = false;
        int missedCount = 0;
        if (record != null) {
            for (int w = 0; w < record.length(); w++) {
                JSONArray week = record.optJSONArray(w);
                if (week == null) {
                    continue;
                }
                for (int d = 0; d < week.length(); d++) {
                    JSONObject cell = week.optJSONObject(d);
                    if (cell == null) {
                        continue;
                    }
                    int day = 0;
                    try {
                        day = Integer.parseInt(cell.optString("date", "0"));
                    } catch (Throwable ignore) {
                    }
                    if (day <= 0) {
                        continue;
                    }
                    Object signed = cell.opt("signed");
                    int state = (signed == null || signed == JSONObject.NULL) ? 2 : (Boolean.TRUE.equals(signed) ? 1 : 0);
                    cells.add(new int[]{day, state});
                    if (day == todayDom && state == 1) {
                        todaySigned = true;
                    }
                    if (state == 0 && day < todayDom) {
                        missedCount++;
                    }
                }
            }
        }

        int acted = 0;

        // ④ 今日签到
        if (todaySigned) {
            log("自动签到：今日（" + today + "）服务端已签到，无需重复");
        } else if (dailyId.isEmpty()) {
            log("自动签到：未取到 daily_id，跳过今日签到");
        } else {
            String msg = postChk(api, c, time, version, c.uid, dailyId, null);
            log("自动签到：今日签到 -> " + msg);
            acted++;
        }

        // ⑤ 补签历史漏签（服务端 signed=false 且早于今天）
        if (AUTO_MAKEUP && missedCount > 0) {
            String mkMonth = cal.get(Calendar.YEAR) + "-" + month;
            int used = mkMonth.equals(sp.getString(K_MAKEUP_MONTH, null)) ? sp.getInt(K_MAKEUP_USED, 0) : 0;
            int quota = Math.max(0, makeupLimit - used);
            if (quota <= 0) {
                log("自动签到：检测到 " + missedCount + " 个漏签日，但本月补签额度已用尽（"
                        + used + "/" + makeupLimit + "）");
            } else {
                int done = 0;
                for (int[] cell : cells) {
                    if (done >= quota) {
                        break;
                    }
                    if (cell[1] == 0 && cell[0] < todayDom && cell[0] <= daysInMonth) {
                        String date = String.format(Locale.US, "%04d-%02d-%02d",
                                cal.get(Calendar.YEAR), month, cell[0]);
                        String msg = postChk(api, c, time, version, c.uid, dailyId, date);
                        log("自动签到：补签 " + date + " -> " + msg);
                        if (msg != null && msg.contains("成功")) {
                            done++;
                            acted++;
                        }
                    }
                }
                sp.edit()
                        .putInt(K_MAKEUP_USED, used + done)
                        .putString(K_MAKEUP_MONTH, mkMonth)
                        .apply();
                log("自动签到：本次补签 " + done + " 天（本月已用 " + (used + done) + "/" + makeupLimit + "）");
            }
        } else if (!AUTO_MAKEUP && missedCount > 0) {
            log("自动签到：检测到 " + missedCount + " 个可补签日（auto_makeup 已关闭，跳过）");
        }

        // ⑥ 全勤奖励（当月每天都已签才可领）
        boolean allSigned = !cells.isEmpty();
        for (int[] cell : cells) {
            if (cell[1] != 1) {
                allSigned = false;
                break;
            }
        }
        if (allSigned) {
            try {
                String body = "data=" + enc(String.valueOf(month));
                JSONObject r = postJson(api + "daily_list/filter?lang=TW", c, time, version, body);
                log("自动签到：全勤奖励 -> " + (r == null ? "(无可领取内容)" : r.toString()));
                acted++;
            } catch (Throwable t) {
                log("自动签到：全勤奖励异常 " + t);
            }
        }

        sp.edit().putString(K_DATE, today).putBoolean(K_OK, true).apply();
        log("自动签到：完成（今日 " + today + "，本次动作 " + acted + " 次）");
        return Outcome.DONE;
    }

    // ------------------------------------------------------------------ 网络

    private static JSONObject getJson(String url, Cred c, long time, String version) {
        try {
            String raw = request("GET", url, null, c, time, version);
            return decodeEnvelope(raw, time);
        } catch (Throwable t) {
            log("请求失败 " + url + " : " + t);
            return null;
        }
    }

    private static String postChk(String api, Cred c, long time, String version,
                                  String uid, String dailyId, String date) {
        try {
            String body = "user_id=" + enc(uid) + "&daily_id=" + enc(dailyId)
                    + (date != null ? "&date=" + enc(date) : "");
            JSONObject r = postJson(api + "daily_chk?lang=TW", c, time, version, body);
            return r == null ? "(解密失败)" : r.optString("msg", r.toString());
        } catch (Throwable t) {
            return "(异常 " + t + ")";
        }
    }

    private static JSONObject postJson(String url, Cred c, long time, String version, String body) {
        try {
            String raw = request("POST", url, body, c, time, version);
            return decodeEnvelope(raw, time);
        } catch (Throwable t) {
            log("POST 失败 " + url + " : " + t);
            return null;
        }
    }

    private static String request(String method, String urlStr, String body, Cred c, long time, String version)
            throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Tokenparam", time + "," + version);
            conn.setRequestProperty("Token", md5(String.valueOf(time) + KEY_SEED));
            if (c.jwt != null && !c.jwt.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + c.jwt);
            }
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) JMComic3/" + version);
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(b.length);
                OutputStream os = conn.getOutputStream();
                os.write(b);
                os.flush();
                os.close();
            }
            int code = conn.getResponseCode();
            InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            in.close();
            return bo.toString("UTF-8");
        } finally {
            try {
                conn.disconnect();
            } catch (Throwable ignore) {
            }
        }
    }

    /** {code,data} -> 解密 data（AES-256-ECB，key=ASCII(md5(time+seed))），两个 seed 依次试。 */
    private static JSONObject decodeEnvelope(String raw, long time) {
        if (raw == null) {
            return null;
        }
        try {
            JSONObject env = new JSONObject(raw);
            if (env.optInt("code", -1) != 200) {
                return null;
            }
            Object data = env.opt("data");
            if (data instanceof JSONObject) {
                return (JSONObject) data;
            }
            if (!(data instanceof String)) {
                return null;
            }
            String b64 = (String) data;
            for (String seed : new String[]{KEY_SEED, SEED_ALT}) {
                try {
                    Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
                    cipher.init(Cipher.DECRYPT_MODE,
                            new SecretKeySpec(md5(String.valueOf(time) + seed).getBytes(StandardCharsets.UTF_8), "AES"));
                    byte[] out = cipher.doFinal(Base64.decode(b64, Base64.DEFAULT));
                    String text = new String(out, StandardCharsets.UTF_8).trim();
                    if (text.startsWith("{")) {
                        return new JSONObject(text);
                    }
                    if (text.startsWith("[")) {
                        JSONObject wrap = new JSONObject();
                        wrap.put("list", new JSONArray(text));
                        return wrap;
                    }
                } catch (Throwable ignore) {
                }
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    /** 备用域名发现：解密 newsvr-2025.txt（与前端 FETCH_HOST 同算法）。 */
    private static String discoverHost() {
        String[] urls = {
                "https://rup4a04-c02.tos-cn-hongkong.bytepluses.com/newsvr-2025.txt",
                "https://rup4a04-c01.tos-ap-southeast-1.bytepluses.com/newsvr-2025.txt",
        };
        String key = md5("diosfjckwpqpdfjkvnqQjsik");
        for (String u : urls) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(u).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                int code = conn.getResponseCode();
                if (code / 100 != 2) {
                    continue;
                }
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                InputStream in = conn.getInputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bo.write(buf, 0, n);
                }
                in.close();
                Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"));
                String text = new String(cipher.doFinal(Base64.decode(bo.toString("UTF-8").trim(), Base64.DEFAULT)),
                        StandardCharsets.UTF_8);
                JSONArray servers = new JSONObject(text).optJSONArray("Server");
                if (servers != null && servers.length() > 0) {
                    return "https://" + servers.optString(0) + "/";
                }
            } catch (Throwable ignore) {
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 凭据

    private static final class Cred {
        String uid;
        String jwt;
        String apiUrl;

        int jwtLen() {
            return jwt == null ? 0 : jwt.length();
        }
    }

    /**
     * 从宿主 WebView 的 localStorage leveldb 里取 uid / jwttoken / apiUrl。
     * 编码事实：Chrome localStorage 含非 ASCII 的值存 UTF-16LE，纯 ASCII 值存 Latin-1。
     * memberInfo 是 UTF-16LE（含中文用户名/繁体文案），apiUrl 与独立的 jwttoken 键是 Latin-1。
     * 因此按编码各解一次、取匹配结果，而不是整块统一解码。
     */
    private static Cred readCreds(Context ctx) {
        Cred c = new Cred();
        File dir = null;
        try {
            dir = new File(ctx.getDataDir(), "app_webview/Default/Local Storage/leveldb");
        } catch (Throwable ignore) {
        }
        if (dir == null || !dir.isDirectory()) {
            File parent = ctx.getFilesDir() != null ? ctx.getFilesDir().getParentFile() : null;
            File alt = parent == null ? null : new File(parent, "app_webview/Default/Local Storage/leveldb");
            if (alt != null && alt.isDirectory()) {
                dir = alt;
            }
        }
        if (dir == null || !dir.isDirectory()) {
            log("自动签到：找不到 localStorage 目录 " + dir + " (exists=" + (dir != null && dir.exists()) + ")");
            return c;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            log("自动签到：目录不可列出 " + dir);
            return c;
        }
        log("自动签到：扫描 " + dir + " 下 " + files.length + " 个文件");
        // 新写入在 .log（后写），先扫 log 再扫 ldb，取最后一次出现的值
        Arrays.sort(files, (a, b) -> {
            boolean la = a.getName().endsWith(".log");
            boolean lb = b.getName().endsWith(".log");
            if (la != lb) {
                return la ? -1 : 1;
            }
            return a.getName().compareTo(b.getName());
        });
        for (File f : files) {
            if (!f.isFile() || f.length() > 8L * 1024 * 1024) {
                continue;
            }
            byte[] buf;
            try {
                buf = readAll(f);
            } catch (Throwable t) {
                continue;
            }
            // memberInfo 的值是 UTF-16LE（含中文时），纯 ASCII 的键值则是 Latin-1；
            // 两种解码各试一遍，正则不会互相误匹配（UTF-16 里 'u' 后面跟着 0x00）。
            String uid = lastGroup(buf, "UTF-16LE", "\"uid\":\"(\\d{4,})\"");
            if (uid == null) {
                uid = lastGroup(buf, "ISO-8859-1", "\"uid\":\"(\\d{4,})\"");
            }
            if (uid != null) {
                c.uid = uid;
            }
            // JWT 三段都可能含 + / = 或 - _（本 App 实测含 +），所以每段都用宽松字符集。
            final String JWTP = "[A-Za-z0-9_\\-+/=]{8,}\\.[A-Za-z0-9_\\-+/=]{8,}\\.[A-Za-z0-9_\\-+/=]{8,}";
            String jwt = lastGroup(buf, "UTF-16LE", "\"jwttoken\":\"(" + JWTP + ")\"");
            if (jwt == null) {
                // 独立的 jwttoken localStorage 键（纯 ASCII 值，Latin-1 存储，值带引号）
                jwt = lastGroup(buf, "ISO-8859-1", "jwttoken.{0,10}\"(" + JWTP + ")\"");
            }
            if (jwt == null) {
                jwt = lastGroup(buf, "UTF-16LE", "\"jwttoken\":\"(" + JWTP + ")");
            }
            if (jwt != null && jwt.length() > c.jwtLen()) {
                c.jwt = jwt;
            }
            String apiUrl = asciiValueAfterKey(buf, "apiUrl", "http");
            if (apiUrl != null) {
                c.apiUrl = apiUrl;
            }
        }
        if (c.uid != null && c.jwt != null) {
            log("自动签到：取到凭据 uid=" + c.uid + " jwtLen=" + c.jwtLen() + " apiUrl=" + c.apiUrl);
        } else {
            log("自动签到：凭据不全 uid=" + c.uid + " jwtLen=" + c.jwtLen() + " apiUrl=" + c.apiUrl);
        }
        return c;
    }

    /** 把整个缓冲区按指定编码解码后，取最后一个匹配的分组 1（leveldb 后写的在后面，天然取最新）。 */
    private static String lastGroup(byte[] buf, String charset, String regex) {
        try {
            String s = new String(buf, charset);
            Matcher m = Pattern.compile(regex).matcher(s);
            String last = null;
            while (m.find()) {
                last = m.group(1);
            }
            return last;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * ASCII 键（如 apiUrl / 独立的 jwttoken）后面紧跟 Latin-1 存的值。
     * 先在原始字节里定位键名，再向后找 valuePrefix（"http"）并读到分隔符。
     */
    private static String asciiValueAfterKey(byte[] buf, String asciiKey, String valuePrefix) {
        byte[] key = asciiKey.getBytes(StandardCharsets.US_ASCII);
        int at = indexOf(buf, key);
        if (at < 0) {
            return null;
        }
        int end = Math.min(buf.length, at + 300);
        byte[] prefix = valuePrefix.getBytes(StandardCharsets.US_ASCII);
        for (int i = at + key.length; i + prefix.length <= end; i++) {
            boolean hit = true;
            for (int j = 0; j < prefix.length; j++) {
                if (buf[i + j] != prefix[j]) {
                    hit = false;
                    break;
                }
            }
            if (!hit) {
                continue;
            }
            StringBuilder sb = new StringBuilder();
            for (int p = i; p < buf.length && sb.length() < 256; p++) {
                int ch = buf[p] & 0xFF;
                if (ch <= 0x20 || ch == '"' || ch == '\\' || ch > 0x7E) {
                    break;
                }
                sb.append((char) ch);
            }
            String s = sb.toString();
            if (s.startsWith("http")) {
                return s;
            }
        }
        return null;
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static byte[] readAll(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream((int) Math.min(f.length(), 1 << 20));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            return bo.toByteArray();
        }
    }

    // ------------------------------------------------------------------ 杂项

    private static String hostVersion(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo("com.a7m3p9xv.t6qk2z8.app", 0);
            return pi.versionName != null ? pi.versionName : "2.1.11";
        } catch (Throwable t) {
            return "2.1.11";
        }
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : d) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private static void log(String msg) {
        XposedBridge.log(TAG + " " + msg);
    }
}

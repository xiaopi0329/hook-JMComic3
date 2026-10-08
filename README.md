# hook-JMComic3

LSPosed 模块：给 **JMComic3**（`com.a7m3p9xv.t6qk2z8.app`，v2.1.11，Capacitor + React）去广告、
繁简转换，并自动领取官方免费 JCoin 签到奖励。

| 项 | 值 |
|---|---|
| 模块名 | `hook-JMComic3` |
| 包名 | `com.jmcomic.hook` |
| 入口类 | `com.jmcomic.hook.MainHook` |
| 版本 | `1.0.0`（versionCode 1） |
| 目标宿主 | `com.a7m3p9xv.t6qk2z8.app` |
| minSdk / targetSdk | 27 / 35 |
| 依赖 | Xposed API 82（`compileOnly`）、DexKit 2.3.0（`implementation`） |

## 功能

### 1. 去广告（双层拦截）

**网络层**：Hook Capacitor 的 `WebViewClient`（`com.getcapacitor.q`）与
`ServiceWorkerClient`（`com.getcapacitor.d`）的 `shouldInterceptRequest`，
命中广告端点时直接返回伪造的 `{code:200, data:AES(空载荷)}`。

- 密钥 `key = ASCII(md5("185Hcomic3PAPP7R"))`，AES-256-ECB；广告端点不带 `time`，故密钥是常量
  （对应前端 `apiPaths.token`）。
- 端点与载荷形状（逐个核对过前端消费方）：
  | 端点 | 载荷 | 为什么 |
  |---|---|---|
  | `ad_content_all` | `{}` | `GET_ADS` 只在 `data[key]` 真值时分发，永不显示 |
  | `advertise_all` | `{"img":{}}` | `Object.keys>0` 才关掉 ThreeCover 的 loading；`img` 为空 → `imgRandomItem=[]`、弹窗门 false |
  | `advertise` | `{"adv":[]}` | 消费方 `return res.data.adv` → 空数组 |
  | `ad_content` | `{"adv":[]}` | 防御：当前前端仅定义未调用 |
- 只按 URL **最后一段**精确匹配，绝不误伤本地静态资源。
- CORS：回显 `Origin` + `Access-Control-Request-Headers`，`OPTIONS` 预检返回 204 空体。

> ⚠️ 载荷不能一律给 `{}`：那样封面组件会永远停在 loading。

**页面层（兜底 + 清缓存）**：Hook `onPageStarted/onPageFinished` 注入 JS——
清掉 localStorage 的 `adsList/adsContent`、短路 `window.fetch` 到同样的预置响应体、
并**持续强制 `memberInfo.ad_free=true`**（关开屏跳过位与首页空 banner 槽）。

> 实现要点：不能只在页面加载时写一次。登录与服务端刷新会经 `saveAuthData()`
> （`Hooks/useAuth.tsx`）整体重写 `memberInfo`，把标志覆盖回 `false`（真机 leveldb 实测
> false/true 交替）。故在 `localStorage.setItem` 上加钩子，所有写入 `memberInfo` 的路径
> 都过 `enforceAdFree()`。

### 2. 繁体 → 简体自动转换

注入 `assets/t2s.js`：OpenCC `TSCharacters`（2,965 单字）+ `TSPhrases` + STPhrases 反转项
+ 586 条手工台湾用语对照（軟體→软件、記憶體→内存、影片→视频 等），最长窗口匹配后退单字。
`MutationObserver` 跟随 React 重渲染实时转换文本节点与
`alt/title/placeholder/aria-label/data-title`（跳过 SCRIPT/STYLE/TEXTAREA，结果不变不写回防循环）。

脚本以 **base64 内嵌进 dex**（`T2S_B64`，构建时从 `assets/t2s.js` 生成）。
运行时读模块 APK 不可行：宿主对模块包有包可见性限制，Android 12+ 安装布局又是
`/data/app/~~hash/pkg-hash/base.apk`，扫目录也落空。内嵌零依赖，解码失败只降级、不影响去广告。

### 3. 每日自动签到（仅官方免费接口）

`AutoCheckin.java`：每天首次启动宿主时自动领取免费 JCoin —— 签到 + 补签 + 全勤奖励。
只调用官方免费接口（`daily` / `daily_chk` / `daily_list/filter`），
**不触碰余额与任何付费解锁**。

- **触发**：`Application.attach`（需要宿主 Context 读凭据并联网），只在主进程挂；
  延迟 8s 起，凭据未就绪时退避重试至多 4 次；每进程一次。
- **每日一次**：SharedPreferences `jm_autocheckin` 的 `run_date`，且以服务端 record 的
  今天 `signed===true` 为准。
- **凭据**：直接读宿主自己的 WebView localStorage leveldb
  （`/data/user/0/<pkg>/app_webview/Default/Local Storage/leveldb`）取 `memberInfo` 的
  `uid`/`jwttoken` 与 `apiUrl`，无需 JS 桥、不依赖页面加载。
  **编码要点**：Chrome localStorage 非 ASCII 值存 **UTF-16LE**、纯 ASCII 存 **Latin-1** ——
  `memberInfo` 是 UTF-16LE，`apiUrl` 与独立的 `jwttoken` 键是 Latin-1，必须按字段分别解。
- **协议**（与宿主 `HttpUtil` 一致）：`Tokenparam: time,version`、
  `Token: md5(time + "185Hcomic3PAPP7R")`、`Authorization: Bearer <jwttoken>`（实测不需要 Cookie）；
  响应 `data` 用 AES-256-ECB（key = `ASCII(md5(time+seed))`，seed 双候选
  `185Hcomic3PAPP7R` / `18comicAPPContent`）解密。
- **补签**：按 record 里 `signed===false` 且早于今天的格子逐日补，受官方 `makeup_limit`
  （缺省 5/月）与前端 `makeup_used` 本地计数约束。
  不想消耗额度就把 `AutoCheckin.AUTO_MAKEUP` 改成 `false`（则只做当日签到 + 全勤）。
- **真机实测**（2026-10-08）：首次启动自动补签 5 天，服务端进度 14.3% → 28.6%，
  已签日期 08 → 01,02,03,05,07,08；同日第二次启动正确跳过（`今日已执行过`）。

### 4. DexKit 兜底

硬编码类名 `com.getcapacitor.q/d`（2.1.11 反混淆结果）失效时，按结构签名在宿主 dex 里重新定位：
继承 `WebViewClient`/`ServiceWorkerClient` + 方法签名，多候选取类名最短者（R8 短名特征）。
`libdexkit.so` 只在硬编码 Hook 失败的路径加载 —— 正常启动零 DexKit 开销。

## 关于 JCoin（付费虚拟货币）

本模块**不包含**任何 JCoin 绕过（改余额、伪造 `coin_buy_*` 响应、白嫖付费章节）。
JCoin 是服务端权威：`memberInfo.coin` 只是 UI 显示值，三笔消耗型接口都是服务端事务请求，
扣费与内容解锁均由服务端裁决，客户端篡改在结构上无效。

模块只做与付费**无关**的增强：广告策略（`ad_free` 显示标记）与界面文案繁→简。

## 构建

**Android Studio**：Open → 选本目录 → 等 Gradle 同步 → `Build → Build APK(s)`。

**命令行**：
```powershell
cd hook-JMComic3
.\gradlew.bat assembleDebug
```
产物：`app\build\outputs\apk\debug\app-debug.apk`

> 首次会自动下载 Gradle 8.11.1（`gradle-wrapper.properties` 已配腾讯镜像）。
> `local.properties` 不入库（已 gitignore），换机器用 Android Studio 打开会自动生成，
> 或手写 `sdk.dir=<你的 SDK 路径>`。
> 工程路径含中文时需要 `gradle.properties` 里的 `android.overridePathCheck=true`（已配）。

## 安装与验证

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb logcat -c
adb logcat | findstr /i hook-JMComic3
```

1. LSPosed → 模块 → 启用 **hook-JMComic3** → 作用域勾 `com.a7m3p9xv.t6qk2z8.app`。
2. 强停 JMComic3 后重新打开（改普通 App 无需重启手机）。
3. logcat 应依次出现：
   - `[hook-JMComic3] 已加载进 com.a7m3p9xv.t6qk2z8.app`
   - `已 Hook com.getcapacitor.q|d.shouldInterceptRequest`
   - `已 Hook q.onPageStarted/onPageFinished（JS 注入）`
   - `繁→简转换 已启用 (N 字符)`
   - 自动签到：`今日已执行过` 或签到成功/补签进度

> 注意：广告请求被 JS 层 fetch 短路，logcat 不会出现 `拦截广告请求`，属正常。

## 排错

| 症状 | 原因 | 修法 |
|---|---|---|
| LSPosed 看不到模块 | manifest 缺 `xposedmodule` meta-data | 对照 `AndroidManifest.xml` |
| 看得到、无日志 | `assets/xposed_init` 路径/类名错；或被混淆改名 | 路径必须 `src/main/assets/`；开混淆须配 `proguard-rules.pro` |
| 有加载日志、无命中 | 包名过滤错 / 类方法签名错 | 用 JADX 核对类全名、方法名、参数类型 |
| 目标 App 闪退 | Hook 回调抛异常 | 每段 Hook 已包 try-catch；注释二分定位 |
| 勾了也不生效 | 作用域没勾 / 装完没强停 | 重勾 → 重装 → 强停 → 重开 |
| `UnsatisfiedLinkError: ... libdexkit.so` | so 没被解压 | 保留 `packaging { jniLibs { useLegacyPackaging = true } }` |
| 自动签到一直"凭据未就绪" | WebView 还没写过 localStorage | 先在 App 里正常登录并翻两页；leveldb 由 UI 进程持有 |

## 红线（改代码时别踩）

- Xposed API 依赖永远 `compileOnly`；DexKit 必须 `implementation` 真打进 APK。
- `handleLoadPackage` 先过滤包名（它对全机每个 App 都触发）。
- Hook 回调永远 try-catch，异常会崩掉目标 App。
- Hook 系统框架才需要重启手机；Hook 普通 App 只需强停。
- 发版必须 `versionCode + 1`，否则 DexKit 触发器认为"模块没变"。
- 包名三处必须一起改：`namespace`/`applicationId`、Java 目录与 `package`、`xposed_init`。

## 文件地图

| 文件 | 作用 |
|---|---|
| `settings.gradle.kts` | 仓库声明：google / mavenCentral / `https://api.xposed.info` |
| `app/build.gradle.kts` | Xposed API `compileOnly`、DexKit `implementation`、`useLegacyPackaging`、`buildConfig`、包名、`versionCode` |
| `app/src/main/AndroidManifest.xml` | Xposed 四件套①：`xposedmodule` 等 4 个 meta-data |
| `app/src/main/res/values/arrays.xml` | Xposed 四件套②：默认作用域（目标 App 包名）|
| `app/src/main/assets/xposed_init` | Xposed 四件套③：入口类全名 |
| `app/src/main/java/com/jmcomic/hook/MainHook.java` | Xposed 四件套④：Hook 入口（去广告 + JS 注入 + DexKit 兜底）|
| `app/src/main/java/com/jmcomic/hook/AutoCheckin.java` | 每日自动签到执行器 |
| `app/src/main/assets/t2s.js` | 繁→简转换表 + DOM 观察器（同时 base64 内嵌进 `T2S_B64`）|
| `app/proguard-rules.pro` | 开混淆时 keep 入口类与 DexKit |

## 参考

- 框架：[LSPosed](https://github.com/LSPosed/LSPosed) · [JingMatrix/Vector](https://github.com/JingMatrix/Vector)（Android 15~17）
- [DexKit 文档](https://luckypray.org/DexKit/en/) · [LuckyPray/DexKit](https://github.com/LuckyPray/DexKit)
- 官方 Wiki：[Develop Xposed Modules Using Modern Xposed API](https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API)

## 免责声明

本项目仅供学习研究，用于个人设备上的界面体验优化。请遵守目标应用的许可协议与当地法律，
使用者需自行承担全部责任。

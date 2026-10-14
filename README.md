# hook-JMComic3

LSPosed 模块：给 **JMComic3**（`com.a7m3p9xv.t6qk2z8.app`，v2.1.11，Capacitor + React）去广告、
繁简转换，并自动领取官方免费 JCoin 签到奖励。

| 项 | 值 |
|---|---|
| 模块名 | `hook-JMComic3` |
| 包名 | `com.jmcomic.hook` |
| 入口类 | `com.jmcomic.hook.MainHook` |
| 版本 | `1.3.0`（versionCode 7）· libxposed API 102 + 热重载 + Kotlin + 设置面板 |
| 目标宿主 | `com.a7m3p9xv.t6qk2z8.app` |
| minSdk / targetSdk | 27 / 35 |
| API | libxposed 现代 API **102**（min=target=102），入口 `XposedModule` + `META-INF/xposed/` 声明；开启**热重载**（`autoHotReload=true`）|
| 语言 | **Kotlin**（模块主体）+ JS（注入补丁）；设置面板经 libxposed service |
| 依赖 | `io.github.libxposed:api:102.0.0`（`compileOnly`）、DexKit 2.3.0（`implementation`） |

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

**启动提速（三级跳，实测回访 1.5s 直达正文）**：

1. **预写捷径**（回访用户）：页面注入时若 `localStorage.apiUrl` 已存在（= 上次选过线路），
   立刻写 `sessionStorage.state=true`（与 ThreeCover.handleAction 同键同值）。Main.tsx 挂载的
   `useEffect([mainStatus])` 直接 `setCoverOpen(6)` —— 选线页根本不出现。真机实测启动 1.5s 见信息流。
2. **选线后捷径**（首次/清数据）：监听 `setItem("apiUrl")`（FirstCover 选定线路时 GlobalStore.updateApiUrl
   恰好写它），同一 tick 写 `state=true`，跳过 switchHost 500ms + SecondCover 3 秒 X + handleAction 400ms。
3. **兜底扫描器**：150ms 轮询，先代点第一条线路（仅当无已存线路；FirstCover 按钮形状 `rounded-full w-2/5`，
   宿主 App.tsx 本来就是随机选线，等价），再点覆盖层按钮（`bg-og w-4/5` 年龄确认 / `rounded-3xl w-8 h-8` X），
   走宿主自己的 `handleAction/onNext` 状态机；状态机没推进时仍能跳完。

旧行为对照：原版每次冷启动要 选线(手点) → 等 3s 点 X → 点"我已年滿18歲" →（可能）再点 X ≈ 7~10 秒。

**选线页出现条件**：只在无已存线路时出现一次；想换线路走 个人中心→SpeedTest（宿主自带，模块不拦）。宿主每次冷启动都走开屏覆盖层链（FirstCover 选线路 → SecondCover 等 3 秒点 X →
ThreeCover 点`我已年滿18歲` → 可能还有 FourCover 点 X）。模块在页面层补丁里监听
`localStorage.setItem("apiUrl")`（FirstCover 选定线路时 `GlobalStore.updateApiUrl` 恰好写这个键）——
触发 20 秒窗口、每 150ms 扫描一次：只点**全屏 z-50 覆盖容器里的 button**（`bg-og w-4/5` 年龄确认大按钮、
`rounded-3xl w-8 h-8` 关闭 X），走宿主自己的 `handleAction/onNext` 状态机推进到正文；绝不伪造
`sessionStorage.state` 之类内部状态，误伤面为零（线路按钮形状不同、广告链接是 `a/Link`、
退出对话框不在全屏 z-50 容器）。

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

### 4. 设置面板（宿主面板）

模块 App 自带设置界面（"宿主面板"）：LSPosed/LSPatch 管理器点模块条目"设置"打开
（manifest activity 带 " + BQ + "de.robv.android.xposed.category.MODULE_SETTINGS" + BQ + "）。

- 5 个开关：去广告 / 繁简转换 / 自动签到 / 自动补签 / 自动跳开屏——经 libxposed
  RemotePreferences（" + BQ + "Config.kt" + BQ + " GROUP=" + BQ + "jm_settings" + BQ + "）实时下发，改完强开宿主即生效。
- 面板进程写入走 XposedService；**宿主进程读取走 XposedInterface.getRemotePreferences**（框架内置通道）。
  注意：LSPatch manager 模式下框架不向宿主投递 service binder（LSPApplication 仅 !useManager 时投递），
  宿主必须用内置通道——这正是 Config.kt 的读取路径。
- **已知边界（真机实测）**：LSPatch 的 RemotePreferences 存于 manager 进程内存，不落盘——
  manager 进程重启后设置回到默认（全部开启）。LSPosed root 模式无此问题。
- 读不到设置（老框架/异常）时全部默认开，与无开关版本行为一致，绝不因读不到而关功能。

### 5. 热重载（API 102）

`module.prop` 设 `autoHotReload=true`：在 LSPosed 里更新模块 APK 后**目标 App 无需强停重开**，
框架自动把模块换代（也可经 libxposed service 手动触发）。

- **旧代** `onHotReloading`：先叫停 `AutoCheckin` 线程（置停机标志 + interrupt + 有界 join 20s），
  再放行重载 —— API 102 契约要求返回 true 前模块自有线程退场。
- **新代** `onHotReloaded`：包生命周期回调**不会重放**，改为遍历 `getOldHookHandles()`，
  按 hook id（`setId`）逐个 `replaceHook` 换成本代 hooker —— 链快照式原子替换，切换**零空窗**；
  无 id 或不认识的旧 hook 一律 `unhook`，绝不让旧代代码留在链上吊住旧 classloader。
- **边界**：热重载当次不重排自动签到（`Application.attach` 已过点），`run_date` 在宿主 SharedPreferences 里，
  下次冷启动照常；若签到线程正阻塞在一次 HTTP 往返（自带 15s 超时）而 20s 未退场，仍放行重载并记日志
  —— 纯 Java 线程无 JNI/native 悬挂风险，旧代随线程结束自然回收。

### 6. DexKit 兜底

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

> ⚠️ **框架要求**：libxposed API 需要 LSPosed **v1.9.0+**（现代 API 支持）；本模块 `minApiVersion=102`，
> 需框架支持 API 102（热重载 / `setId` / `replaceHook`，对应 LSPosed v2.x 代），旧框架会直接判模块不兼容。
> 老版 LSPosed / EdXposed / Tai chi 不认本模块，装上也只会静默不生效。

> Kotlin 迁移后首次构建需联网下 Kotlin 插件（腾讯镜像已配 gradle wrapper）。

**命令行**：
```powershell
cd hook-JMComic3
.\gradlew.bat assembleDebug
```
产物：`app\build\outputs\apk\debug\app-debug.apk`

> 签名：debug / release 统一用工作区 keystore `D:\工作区\apk签名\xiaopi key.jks`
> （证书指纹 SHA256 `89:DA:2A:A8:CA:88:B2:3D:9E:12:90:7C:3B:48:7F:38:9A:BD:79:7E:A1:0C:81:83:EB:96:01:65:D6:E5:74:AA`）。
> 换签名后手机上旧签名的模块装不上，先 `adb uninstall com.jmcomic.hook` 再 `adb install -r`。

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
| LSPosed 看不到模块 | LSPosed 版本 <1.9.0（不认现代 API）；或缺 `META-INF/xposed/java_init.list` | 升级 LSPosed；`unzip -p app-debug.apk META-INF/xposed/java_init.list` 确认资源打进包 |
| 看得到、无日志 | `java_init.list` 类名错/被混淆改名；或 `module.prop` 的 minApiVersion 高于框架 | 路径必须 `src/main/resources/META-INF/xposed/`；开混淆须配 `proguard-rules.pro` |
| 有加载日志、无命中 | 包名过滤错 / 类方法签名错 | 用 JADX 核对类全名、方法名、参数类型 |
| 更新模块后行为没变 | 热重载被框架拒绝（框架不支持 102 / `onHotReloading` 未放行） | logcat 找 `热重载完成: 替换 N`；或照旧强停重开宿主 |
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
- 包名三处必须一起改：`namespace`/`applicationId`、Java 目录与 `package`、`java_init.list`。
- 不再写任何 `de.robv.*`：target 102 的模块调 legacy API 会被框架直接禁止（API_102 行为变更）。
- 新增 hook 必须给稳定 id（`setId(H_*)`）并在 `hookerForId` 登记重建分支，否则热重载后该 hook 会被新代卸掉。

## 文件地图

| 文件 | 作用 |
|---|---|
| `settings.gradle.kts` | 仓库声明：google / mavenCentral |
| `build.gradle.kts` | AGP 8.13.2 + Kotlin 2.0.21（compileSdk 37——libxposed service AAR 硬要求 ≥37；targetSdk 35/minSdk 27 不变）|
| `app/build.gradle.kts` | Xposed API `compileOnly`、DexKit `implementation`、`useLegacyPackaging`、`buildConfig`、包名、`versionCode` |
| `app/src/main/AndroidManifest.xml` | `android:label`/`android:description` = 模块名与描述（现代 API 不再用 meta-data）|
| `app/src/main/resources/META-INF/xposed/java_init.list` | Java 入口类全名（替代 `assets/xposed_init`）|
| `app/src/main/resources/META-INF/xposed/module.prop` | `minApiVersion=102` / `targetApiVersion=102` / `autoHotReload=true` |
| `app/src/main/resources/META-INF/xposed/scope.list` | 默认作用域（目标 App 包名，一行一个）|
| `app/src/main/java/com/jmcomic/hook/MainHook.kt` | Hook 入口 `: XposedModule()`（去广告 + JS 注入 + DexKit 兜底 + 热重载）|
| `app/src/main/java/com/jmcomic/hook/AutoCheckin.kt` | 每日自动签到执行器（object）|
| `app/src/main/java/com/jmcomic/hook/Config.kt` | 设置桥：宿主经 XposedInterface.getRemotePreferences 读开关 |
| `app/src/main/java/com/jmcomic/hook/SettingsActivity.kt` | 设置面板（管理器 MODULE_SETTINGS 入口，纯代码布局）|
| `app/src/main/assets/t2s.js` | 繁→简转换表 + DOM 观察器（同时 base64 内嵌进 `T2S_B64`）|
| `app/proguard-rules.pro` | 开混淆时 keep 入口类与 DexKit |

## 参考

- API：libxposed 现代 API（Maven Central `io.github.libxposed:api`，源码见 github.com/libxposed/api）
- 框架：[LSPosed](https://github.com/LSPosed/LSPosed)（**v1.9.0+** 才支持现代 API）· [JingMatrix/Vector](https://github.com/JingMatrix/Vector)（Android 15~17）
- [DexKit 文档](https://luckypray.org/DexKit/en/) · [LuckyPray/DexKit](https://github.com/LuckyPray/DexKit)
- 官方 Wiki：[Develop Xposed Modules Using Modern Xposed API](https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API)

## 免责声明

本项目仅供学习研究，用于个人设备上的界面体验优化。请遵守目标应用的许可协议与当地法律，
使用者需自行承担全部责任。

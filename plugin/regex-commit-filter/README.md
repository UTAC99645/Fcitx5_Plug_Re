# Regex Commit Filter — fcitx5-android 插件

在市面上的输入法里，上屏前改字通常只能靠输入法自带功能。这个插件把「上屏前改写」做成独立 APK：
用户在小企鹅输入法里打出文字、候选上屏的那一刻，插件按用户自定义的有序规则做 **RE2 正则匹配 + 替换**，
替换后的文本才真正进入目标应用。

- 所有规则、样例文本、匹配结果都只在本机内存/私有目录里处理。
- **不联网、不上传、不写日志**（插件不请求网络权限，代码中也从不打印上屏文本）。
- 规则改动后**无需重启输入法**：UI 把规则通过本机抽象 Unix 套接字推给原生 addon，addon 原子替换已编译规则集。

## 一、组成与数据流

```
┌─────────────────────────────┐        abstract AF_UNIX socket
│ 插件 APK（独立进程/UID）      │   "<主程序包名>.regex_commit.rules"
│  Kotlin UI + re2j 校验/预览   │ ───────────────────────────────┐
│  files/rules.json（私有目录） │                                │
│  lib/<abi>/libregexcommit.so │                                ▼
└─────────────────────────────┘        ┌──────────────────────────────────┐
                                       │ 小企鹅输入法主程序                 │
                                       │ fcitx5 Instance                  │
                                       │  └─ addon: regexcommit           │
                                       │      watchEvent(CommitString,    │
                                       │        PostInputMethod)          │
                                       │      → RE2 规则链 → 改写并重提交   │
                                       │      config/regex-commit-filter/ │
                                       │        rules.json（持久副本）     │
                                       └──────────────────────────────────┘
```

关键点：

1. 插件 APK 包名以 `org.fcitx.fcitx5.android.plugin.` 开头，`res/xml/plugin.xml` 声明插件元数据；
   构建期 data-descriptor 生成 `assets/descriptor.json`。
2. 主程序启动时扫描该前缀的已安装包，把插件 `assets/usr/` 合并进自己的数据目录，并把插件的
   `nativeLibraryDir` 追加到 `FCITX_ADDON_DIRS`；fcitx5 从该目录 `dlopen` `libregexcommit.so`。
3. addon 在 `PostInputMethod` 阶段监听 `InputContextCommitString` / `InputContextCommitStringWithCursor`。
   若需要改写：先 `event.accept()` 抑制本次原始提交，再在当前输入上下文内 `commitString(改写后文本)`；
   用重入标志保证不会二次处理。`CommitStringEvent` 本身没有 setter，这是零补丁方案的核心。
4. 密码框/敏感输入：`CapabilityFlag::Password` / `Sensitive` 直接跳过；`NoSpellCheck`
   （对应 Android 的 `TYPE_TEXT_FLAG_NO_SUGGESTIONS`）是否跳过由全局开关决定。
5. 所有异常、超限、UTF-8 非法、正则编译失败一律**原样上屏**，绝不阻塞输入线程。
6. 套接字用抽象命名空间（内核里 `sun_path[0] = '\0'`）。由于不同 Android 版本的
   `android.net.LocalSocket` 传入的地址长度不同，addon 对同一名字**同时绑定「精确长度」与
   `sizeof(sockaddr_un)` 两种抽象名**，客户端两种实现都能连上。

## 二、规则模型

规则文件 JSON（插件侧 `files/rules.json`，addon 侧持久副本
`<FCITX_CONFIG_HOME>/regex-commit-filter/rules.json`）：

```json
{
  "version": 1,
  "skipNoSpellCheck": false,
  "rules": [
    {
      "id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
      "enabled": true,
      "name": "把手机号中间四位打码",
      "pattern": "(?P<pre>1[3-9]\\d)(\\d{4})(?P<post>\\d{4})",
      "replacement": "${pre}****${post}",
      "scope": { "mode": "blacklist", "packages": ["com.example.app"] },
      "applyOncePerCommit": true,
      "stopOnFirstMatch": false,
      "caseInsensitive": false,
      "unescapeReplacement": false
    }
  ]
}
```

| 字段 | 含义 |
| --- | --- |
| `enabled` | 停用的规则不编译、不执行 |
| `scope.mode` | `blacklist`（只在名单之外生效，默认）/ `whitelist`（只在名单内生效） |
| `scope.packages` | 空数组表示不限制 |
| `applyOncePerCommit` | `true` 只替换第一处（`replaceFirst`），`false` 全局替换（`replaceAll`） |
| `stopOnFirstMatch` | 本条命中后跳过后续规则 |
| `caseInsensitive` | 等价于在正则前写 `(?i)` |
| `unescapeReplacement` | 替换串里额外解释 `\n` `\r` `\t` `\0` `\\` |

执行语义：按数组顺序串行，**上一条的输出是下一条的输入**；规则集在 UI 保存时全量推送，
addon 只有在全部规则都编译通过时才原子替换（任何一条失败则整份不生效并回传错误）。

防失控保护（Kotlin 预览与 C++ 运行时一致）：

- 单次上屏文本 **4096 字节**（UTF-8，非字符数）以上直接跳过；
- 单条规则最多 **1000 次**替换，超出则本条规则整条回退；
- 单条规则累计匹配耗时上限 **20 ms**，超时则本条回退；
- 空匹配（如 `a*`）用「双游标」推进，避免死循环；
- 输出再做一次 UTF-8 校验，非法则回退。

## 三、替换串语法

| 语法 | 含义 |
| --- | --- |
| `$1` / `$12` | 编号捕获组 |
| `${1}` | 编号捕获组（显式定界） |
| `${name}` | 命名捕获组 `(?P<name>...)` |
| `$$` | 字面 `$` |
| `\n` `\r` `\t` `\0` `\\` | 仅在打开 `unescapeReplacement` 时解释 |

没有条件替换、没有函数调用、没有 `eval`，任何模板都只会做字符串拼接。
替换串中引用不存在的组会在保存时被拒绝并给出可读错误。

## 四、RE2 语法支持清单

运行时使用 C++ [RE2](https://github.com/google/re2)（2024-07-02 + abseil 20240722.1）；
插件 UI 的校验与「试一试」使用 [re2j](https://github.com/google/re2j) 1.8（同一 RE2 语义）。

**支持**

- 命名捕获组 `(?P<name>re)`、非捕获组 `(?:re)`
- Unicode 属性类 `\p{Han}`、`\p{Lu}`、`\p{L}` 等
- POSIX 字符类 `[[:alpha:]]`
- 字面量引用 `\Q...\E`
- 内联标志 `(?i)` `(?s)` `(?m)` `(?U)`
- 非贪婪量词 `x*?`、`x+?`、`x??`
- 常见转义 `\d` `\w` `\s`、十六进制 `\x41`、`\p{...}`

**明确不支持（保存时静态扫描拒绝，不会到运行时才崩）**

| 构造 | 示例 | 报错类型 |
| --- | --- | --- |
| 前瞻 | `foo(?=bar)` / `foo(?!bar)` | `LOOKAHEAD` |
| 后顾 | `(?<=foo)bar` / `(?<!foo)bar` | `LOOKBEHIND` |
| 反向引用 | `(a)\1` | `BACKREFERENCE` |
| 占有优先量词 | `a++`、`a*+`、`a?+` | `POSSESSIVE` |
| 原子组 | `(?>a)` | `ATOMIC_GROUP` |

另外：`\1`~`\9` 形式会被当作反向引用拒绝，因此也不要用八进制转义；需要字面反斜杠请写 `\\`。

## 五、隐私与安全

- 插件 `AndroidManifest.xml` 不声明 `INTERNET` 权限，规则与文本无法外传。
- 不写 Logcat、不写任何日志文件、不上报统计；C++ RE2 设置为 `log_errors=false`，编译失败也不打印正则原文。
- 规则文件在应用私有目录（插件 `filesDir`、主程序 `config/regex-commit-filter/`），不落外部存储。
- 密码/敏感输入框跳过（`Password`、`Sensitive` capability）。
- UI 顶部常驻隐私声明，README 同步声明。

## 六、构建

环境：Android SDK（compileSdk 由仓库根 `libs.versions.toml` 指定）、NDK、CMake。
首次构建会通过 `FetchContent` 拉取 abseil 与 RE2（需要网络），之后走缓存。

```bash
# 只编插件 debug APK（四种 ABI 与主工程一致）
./gradlew :plugin:regex-commit-filter:assembleDebug

# release（会走 proguard）
./gradlew :plugin:regex-commit-filter:assembleRelease
```

产物在 `plugin/regex-commit-filter/build/outputs/apk/debug/`，文件名形如
`org.fcitx.fcitx5.android.plugin.regex_commit-<versionName>-<abi>-debug.apk`。

安装（ABI split 会产出多个 APK，按设备 ABI 装其中一个；或一次性全部安装）：

```bash
adb install -r plugin/regex-commit-filter/build/outputs/apk/debug/org.fcitx.fcitx5.android.plugin.regex_commit-*-arm64-v8a-debug.apk
# 或：
adb install-multiple -r plugin/regex-commit-filter/build/outputs/apk/debug/*.apk
```

**首次安装后请重启一次小企鹅输入法（或其宿主 App）**：主程序只在启动时扫描插件包，
重启后才会把 `libregexcommit.so` 加进 addon 搜索路径并加载 addon。之后改规则即时生效。

> debug 变体的包名带 `.debug` 后缀，且 `BuildConfig.MAIN_APPLICATION_ID` 会变成
> `org.fcitx.fcitx5.android.debug`（套接字名随之变化）。**debug 插件请搭配 debug 版主程序**，
> release 插件搭配 release 版主程序，否则同步会找不到 addon。

## 七、手动验收

前提：已完成上面两步，并且在系统设置里启用了小企鹅输入法。

1. 打开小企鹅输入法 → 「插件」页 → 点击本插件条目进入规则管理界面，确认顶部显示「已同步」。
2. 新增规则：名称随意，正则
   `(?P<pre>1[3-9]\d)(\d{4})(?P<post>\d{4})`，替换 `${pre}****${post}`，只替换第一处。
3. 在任意聊天/记事 App 里用输入法输入 `13812345678`，期望上屏为 `138****5678`。
4. 新建 `\p{Han}` → `字` 的规则（全局替换），输入 `中文abc`，期望 `字字abc`。
5. 新建 `(?i)hello` → `hi`，输入 `HeLLo hello`，期望 `hi hi`（验证内联标志）。
6. 新建 `a.*?b` → `X`，输入 `a1b a22b`，期望 `X X`（验证非贪婪）。
7. 规则 A：`a`→`b`；规则 B：`b`→`c`。输入 `a`，期望 `c`（验证链式顺序）。
8. 系统「设置 → 修改锁屏密码」的密码输入框里输入同样数字，期望**保持原样**
   （验证密码框跳过）。
9. 停用第 2 条规则后立刻再输入手机号，期望不再打码（验证热重载，无需重启）。

## 八、测试

### 宿主机 C++ 引擎测试（不依赖 Android）

```bash
cmake -S plugin/regex-commit-filter/src/main/cpp/test -B /tmp/regexcommit-host-build -G Ninja \
      -DCMAKE_BUILD_TYPE=Release
cmake --build /tmp/regexcommit-host-build -j
/tmp/regexcommit-host-build/rule_engine_tests
```

覆盖：普通替换、命名组、`\p{Han}`、内联 `(?i)`、非贪婪、前瞻拒绝、超长跳过、链式规则、
`stopOnFirstMatch`、白名单/黑名单、`$$`、NO_SUGGESTIONS、只替换第一处、停用规则、空文本。
全部通过时打印 `ALL TESTS PASSED` 并以 0 退出。

### Kotlin 单元测试（re2j 侧，校验/预览语义）

```bash
./gradlew :plugin:regex-commit-filter:testDebugUnitTest
```

用例在 `src/test/java/.../core/RuleEngineTest.kt`，与上面 C++ 用例对齐，另加：
后顾拒绝、反向引用拒绝、占有量词拒绝、未知替换组拒绝、预览分组展示、编译错误展示、转义展开。

> re2j 1.8 的语义已单独验证：`\p{Han}`、`\p{Lu}`、`[[:alpha:]]`、`\Q..\E`、
> `(?i)(?s)(?m)(?U)`、`x*?` 均按 RE2 行为工作；`(?=`/`(?<=`/`\1`/`a++`/`(?>a)` 均在编译期抛出
> `PatternSyntaxException`，与保存时校验一致。

## 九、故障排查

| 现象 | 排查 |
| --- | --- |
| 插件没有被主程序识别 | 确认包名以 `org.fcitx.fcitx5.android.plugin.` 开头；确认 APK 里有 `assets/descriptor.json` 与 `assets/usr/...`；`adb shell dumpsys package org.fcitx.fcitx5.android.plugin.regex_commit \| head` 看安装是否成功；**重启输入法**后重试 |
| 「插件」页找不到入口 | `res/xml/plugin.xml` 是否声明 `domain`；Activity 的 intent-filter 是否为 `action = "<主程序包名>.plugin.MANIFEST"`（注意 `MANIFEST` 常量名要与主工程一致） |
| UI 显示「同步失败」 | addon 未加载或套接字连不上。`adb logcat \| grep -i regexcommit` 看是否 `Loaded addon regexcommit`；规则推送依赖抽象套接字名 `"<主程序包名>.regex_commit.rules"`，可用 `adb shell cat /proc/net/unix \| grep regex_commit` 查看是否存在 |
| logcat 出现 `avc: denied` 且涉及 `unix_stream_socket connectto` | 不同 UID 的应用访问抽象套接字被 SELinux 拦截（见「已知限制」）。先确认设备策略；可临时用 `adb shell setenforce 0` 验证是否为该原因（仅调试，不要长期关闭） |
| 规则不生效 | 确认规则已启用、包名 scope 未排除当前 App、文本不超过 4 KB、命中次数不超过 1000；检查是否勾选「跳过无个性化学习字段」而当前输入框带 `NO_SUGGESTIONS` |
| 正则保存时报错 | 按 UI 给出的错误类型修正：前瞻/后顾/反向引用/占有量词/原子组均为 RE2 不支持项 |
| 输入法重启后规则丢失 | addon 的持久副本在 `<主程序 files>/config/regex-commit-filter/rules.json`；若插件进程从未成功推送过，该文件不存在。打开插件界面点一次保存即可 |

## 十、已知限制

1. **cursor 丢失**：走 `CommitStringWithCursorEvent` 时，改写后通过 `commitString()` 重提交，
   客户端收到的是无 cursor 的提交（等价 cursor = -1）。仅影响需要精确定位光标的 IME 场景。
2. **跨进程通信依赖抽象套接字**：插件与主程序 UID 不同，部分厂商 ROM 的 SELinux 策略可能拒绝
   `connectto`。若被拒绝，规则无法热推送（可看到「同步失败」），但不影响输入本身。
3. 只覆盖「经 fcitx5 引擎上屏」的文本；系统粘贴、其他输入法、应用内直接 `setText` 不经过该链路。
4. `NUMBER_VARIATION_PASSWORD` 在 fcitx5 的能力位里没有独立映射，只能依赖 `Password` 位；
   极少数实现不设置该位的输入框可能被漏过（可在包名黑名单里补充规避）。
5. 规则校验以「保存时」为准；若手工编辑 `rules.json` 塞入非法正则，addon 会拒绝整份规则并保持旧规则集。
6. 不支持反向引用、前瞻/后顾、占有量词、原子组（RE2 本身不提供）。
7. 规则链很长时，最坏耗时是各条规则的 20 ms 上限之和；上屏路径仍受 4 KB 文本上限约束。

## 十一、许可证与第三方依赖

本项目与上游一致使用 **LGPL-2.1-or-later**。

| 依赖 | 版本 | 许可证 | 用途 |
| --- | --- | --- | --- |
| [google/re2](https://github.com/google/re2) | 2024-07-02 | BSD-3-Clause | C++ 运行时正则引擎 |
| [abseil-cpp](https://github.com/abseil/abseil-cpp) | 20240722.1 | Apache-2.0 | RE2 的依赖 |
| [google/re2j](https://github.com/google/re2j) | 1.8 | BSD-3-Clause | Kotlin 侧校验与预览 |
| [nlohmann/json](https://github.com/nlohmann/json) | 3.11.3 | MIT | addon 侧 JSON 解析（单头文件） |

许可元数据位于 `licenses/libraries/*.json`，由构建期 AboutLibraries 任务读取并打包。

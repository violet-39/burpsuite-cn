# Burp Suite 中文语言包

给 [Burp Suite](https://portswigger.net/burp) 用的界面汉化包。纯 Java Agent 实现，**不修改 Burp 本体**，不改动授权逻辑，随时可以一键切回英文。

适配 **Burp Suite 2026.11.2**（内置 Java 25），同时兼容 Java 21 / 24。

## 为什么需要它

社区里流传最广的汉化包（`BurpSuiteCN`，2024 年构建）在新版 Burp 上已经用不了了，两个硬伤：

1. 它依赖 `jdk.internal.org.objectweb.asm`，这个内部类在 **JDK 25 已被移除**，启动即 `NoClassDefFoundError`，进程直接退出；
2. 它的注入层用 javassist 3.29 改写 JDK 类，在 JDK 25 上生成的字节码过不了校验（`javax.swing.JDialog` 上抛 `VerifyError`）。

本项目重写了整个注入层，词库沿用原项目（Apache-2.0），因此在新运行时上也能正常工作。

## 特性

- 只翻界面文本，**不动请求/响应报文、日志、编辑器内容**
- 覆盖菜单、按钮、标签、提示气泡、窗口标题、选项卡、设置树、表格表头、搜索框占位符
- 不翻译技术记号：HTTP 方法、MIME 类型、状态码、正则、文件路径等（由白名单保护）
- 支持外置词库 `cn-*.txt` 覆盖内置译文，方便自行补充
- 未翻译的字符串会记录到 `log/unTranslate.txt`，便于持续完善

## 安装

1. 从 [Releases](../../releases) 下载 `burpsuite-cn.jar`，放进 Burp 安装目录（和 `burpsuite.jar` 同级）：

   | 系统 | 安装目录 |
   |---|---|
   | Windows | `C:\Program Files\BurpSuiteProfessional\` 或自定义目录 |
   | macOS | `/Applications/Burp Suite Professional.app/Contents/` |
   | Linux | Burp 解压目录 |

2. 在同目录新建 `user.vmoptions`（Windows 也可用 `%APPDATA%\BurpSuite\` 下的同名文件），写入一行：

   ```
   -javaagent:<安装目录>/burpsuite-cn.jar=han
   ```

   Windows 示例：

   ```
   -javaagent:D:\BurpSuite\BurpSuite\burpsuite-cn.jar=han
   ```

   > 注意：整行必须在同一行，路径含空格时用引号包住。`=han` 表示只启用汉化。

3. 重启 Burp，界面即为中文。

## 关闭汉化

删掉（或注释掉）`user.vmoptions` 里那行 `-javaagent`，重启即为英文。jar 不用删。

## 自定义词库

在安装目录新建 `cn-my.txt`，每行 `原文<Tab>译文`（中间必须是 Tab）：

```
Send to Repeater	发送到重放器
My custom label	我的自定义标签
```

- 文件名以 `cn` 开头、`.txt` 结尾即可，可以放多个
- 外置词库会覆盖内置同名条目
- 译文中含 `$1` 这类反向引用时按正则处理：

```
Burp Suite Community Edition v(.*)	Burp Suite 社区版 v$1
```

想查哪些文字还没翻译：安装目录下的 `log/unTranslate.txt` 会自动记录所有未命中的原文。

## 已知限制

- **内嵌网页无法翻译**：Burp 的 Academy、Discover 标签页正文由内嵌 Chromium（`burp/Zcef`）渲染，不是 Java 组件，Java 层汉化覆盖不到，只有标签名可翻。
- **多行编辑区刻意不翻**：请求/响应报文、日志、Bambda 编辑器里的内容保持原样，避免影响你的输入和抓包数据。
- 直接绘制文本的自定义控件、HTML 富文本中的个别片段可能漏翻。
- Burp 每次大版本更新都会新增文案，需要持续补词库。

## 原理

```
-javaagent → Agent.premain
  ├─ appendToBootstrapClassLoaderSearch(this jar)   // 让 bootstrap 加载的 JDK 类能直接调用翻译类
  └─ addTransformer(CnTransformer)                  // ASM 注入器

CnTransformer：在目标方法入口插入一行
  argN = CnTranslator.translate([this,] "cn", argN);
```

几个关键取舍：

- **用 ASM 而不是 javassist**：只在方法开头插入直线代码，不产生分支，因此不需要重算 StackMapTable，生成的字节码在 JDK 21~25 都能通过校验。
- **挂到 bootstrap 类路径**：`javax.swing.*` 由 bootstrap 加载，看不到应用类路径，挂载后可以直接 `INVOKESTATIC` 调用翻译类，省掉原版那套 `ClassLoader.loadClass` + 反射。
- **按组件类型过滤**：`JTextArea`、可编辑的 `JTextComponent`、`PlainDocument` 一律跳过，保证用户内容不被误翻；`JTextField` 只放行单行（占位符场景）。

注入点：

| 类 | 方法 |
|---|---|
| `java.awt.Frame` / `java.awt.Dialog` | `setTitle` |
| `javax.swing.JLabel` / `AbstractButton` | `setText` |
| `javax.swing.text.JTextComponent` / `JEditorPane` | `setText` |
| `javax.swing.text.AbstractDocument` | `insertString` |
| `javax.swing.JComponent` | `setToolTipText` |
| `javax.swing.JTabbedPane` | `addTab` / `insertTab` |
| `javax.swing.JOptionPane` | `showOptionDialog` / `showMessageDialog` / `showInputDialog` |

## 构建

需要 JDK 8+（推荐 21）。ASM 会由脚本自动从 Maven Central 下载。

```bash
# Linux / macOS
bash build.sh

# Windows
build.bat
```

产物：`burpsuite-cn.jar`。

## 目录结构

```
src/leon406/Agent.java           premain，挂载 bootstrap + 注册注入器
src/leon406/CnTransformer.java   ASM 注入器，维护注入点表
src/leon406/CnTranslator.java    翻译核心：字面量 + 正则 + 白名单 + LRU 缓存
src/leon406/LruCache.java        LRU 缓存
res/cn.txt                       词库（原文 <Tab> 译文）
res/white.txt                    白名单（不翻译的规则）
manifest.txt                     Premain-Class 等清单
```

## 免责声明

- 本项目**只做界面文本翻译**，不包含任何破解、授权绕过或 loader，也不修改 Burp Suite 本体文件。
- Burp Suite 是 [PortSwigger](https://portswigger.net) 的商业软件，请遵守其许可协议使用。学习和轻量场景建议使用免费的 Community 版。
- 汉化包属于第三方非官方补丁，升级 Burp 后可能需要更新。

## 许可与致谢

- 词库（`res/cn.txt`、`res/white.txt`）来自 [Leon406/BurpSuiteCN-Release](https://github.com/Leon406/BurpSuiteCN-Release)，Apache License 2.0。
- 注入层为本项目重写。
- 运行时依赖 [ASM](https://gitlab.ow2.org/asm/asm)（BSD 3-Clause）。

本项目以 Apache License 2.0 发布，详见 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)。

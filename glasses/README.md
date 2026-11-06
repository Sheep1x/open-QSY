# 题眼 · 眼镜端（GlassEyes）

Rokid Glasses **裸机（bare-metal）** 单机应用：不连手机、不联网，眼镜上直接
**定时拍照识题 → 离线 OCR → 本地题库匹配 → 屏上显示答案**（每 3 秒自动拍一张，无需按键）。

- 运行环境：YodaOS-Sprite（Android 12 / API 31，Android Go），480×640 单绿显示
- 工程来源：基于 Rokid 官方 `GlassesBareDevSample`（含其 480×640 线框 UI 体系与按键分发），
  已改包名 `com.openqsy.glasses` 并裁掉音频/录像/IMU 示例，只保留拍题链路
- 题库：由 PC 端 `tools/build_qbank.py` 把 `题库合并.json` 编译成 `app/src/main/assets/qbank.idx`
  （6622 题 → **0.91 MB**，查询 1~3 ms，PC 侧实测 top1 98%）

## 目录

```
glasses/
├── GlassEyes/                     # Android 工程（Android Studio 直接 Open 这个目录）
│   ├── app/src/main/assets/qbank.idx      # 编译产物，随 APK 打包
│   └── app/src/main/java/com/openqsy/glasses/
│       ├── activities/main/MainActivity.kt   # 单 Activity + 按键分发
│       ├── ui/screen/QuizScreen.kt           # 一拍一答的眼镜界面
│       ├── quiz/QBankStore.kt                # 索引读取 + 检索（无第三方依赖）
│       ├── quiz/TextNormalizer.kt            # 归一化，必须与 Python 端逐条一致
│       ├── ocr/OcrEngine.kt                  # OCR 抽象（换引擎只改这一层）
│       ├── ocr/MlKitOcrEngine.kt             # ML Kit 中文离线识别（默认）
│       ├── solve/SolveViewModel.kt           # 状态机：拍摄→识别→匹配→出答案
│       ├── ai/AiConfig.kt                    # 第三方 API 配置（baseUrl / Key / 模型）
│       ├── ai/AiClient.kt                    # OpenAI 兼容调用：拉模型列表 + 一问一答
│       ├── transfer/QBankUploadServer.kt     # 局域网 HTTP：换题库上传 + AI 配置页（/ai）
│       ├── input/                            # 官方按键分发（TouchPad / 镜腿）
│       └── ui/design/                        # 官方 480×640 线框 UI 体系
└── tools/
    ├── build_qbank.py             # 题库编译 + 内置噪声自检（--self-test）
    ├── serve_bank.py              # 把题库发布成局域网地址并生成二维码（换题库用）
    ├── dump_idx.py                # 抽查 qbank.idx：题数/词条/题干/答案是否齐全
    ├── pdf_to_text.py             # pypdf 抽 PDF 文本（去页眉/页脚/页码）
    ├── pdf06_bank.py              # 把《习题模拟》PDF 解析成题库（答案分离结构）
    ├── pdf06_tables.py            # 把 PDF 里抽成散行的表格还原成可读结构
    ├── merge_bank.py              # 多份题库按题干归一化去重合并
    ├── pdf_batch_probe.py         # 批量探查 PDF 排版（讲/大节/题号/答案区），解析前先摸底
    ├── pdf_dir_bank.py            # 把「题目答案分离」PDF 整个目录解析成题库
    ├── pdf_dir_debug.py           # 单份 PDF 的切块与配对诊断（缺答案时用）
    ├── merge_dir_bank.py          # 把目录解析结果并进 gugu（归一化去重）
    ├── gen_big_bank.py            # 造大题量题库（复制变体），压测编译耗时与内存上限
    ├── layout_preview.py          # PC 上模拟 480×640 排版，检查重叠/越界
    └── ai_mock.py                 # 假的 OpenAI 服务：验收「拉模型/问 AI」，并证明命中题库时不联网
```

## 构建与装机

```bash
# 0. 前置：Rokid AI App 里打开眼镜 ADB；必须用「数据调试线」（充电线不行）
adb devices                      # 应看到 Rokid RG-glasses 一类设备

# 1. 换题库（可选）：改了题库 JSON 后重新编译索引
python tools/build_qbank.py --input ../../题库合并.json \
       --output GlassEyes/app/src/main/assets/qbank.idx --self-test

# 2. 构建
cd GlassEyes
./gradlew :app:assembleDebug

# 3. 安装并启动
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.openqsy.glasses/.activities.main.MainActivity

# 4. 看日志 / 投屏
adb logcat -v time --pid "$(adb shell pidof -s com.openqsy.glasses)"
scrcpy --window-title 题眼          # 480×640 同屏，便于确认 UI
```

**换题库（零门槛，会扫二维码就行）**：电脑（或任何能放文件的地方）把题库变成一个网址，
把网址做成二维码，眼镜扫一下 → 下载 → 现场编译 → **几秒内自动生效，不用重启、不用重装**。

- 电脑上一行命令就够：`python glasses/tools/serve_bank.py 题库合并.json`
  （脚本会起局域网服务、打印地址、弹出二维码）；
- 不在同一网络也行：`--url https://…/qbank.json`，任何文件直链都能扫；
- 支持 `.json`（可直接传 PC 端那个 `题库合并.json`，眼镜自己编译）和 `.idx`；
- 设备端编译结果与 PC 端 `build_qbank.py` **逐字节相同**（md5 `210aeb0b…`，1.9 MB / 6622 题约 1.7 秒）；
- 扫到的文件落在应用私有目录并持久，下次启动仍然生效；
- 也能「放进设备就生效」：`adb push 题库合并.json /sdcard/Download/` 后重启应用，
  `QBankLoader.discover()` 会按修改时间挑最新的一个（需授予存储权限）；
- **题库能放多大**：单份上限 64 MB（`BankDownloader.MAX_BYTES`），约 8 万题。约束不在下载，
  而在设备端编译的内存与耗时（`IndexBuilder` 用 `JsonLite` 流式扫描，不再建整棵 JSON 对象树）。
  实测（`tools/gen_big_bank.py` 造的压测库）：4.6 MB / 5,933 题编译 1.9 秒、峰值 151 MB；
  39.5 MB / 53,397 题编译 33.6 秒、峰值 289 MB、索引 24 MB。检索耗时几乎不随题量变
  （5.3 万题单次 7~9 ms——走倒排索引，不是全表扫）。再大请拆成两份轮换用；
- 详见 `docs/04-换题库指南.md`。

## 交互

| 动作 | 硬件事件 | 行为 |
|---|---|---|
| 单击 | TouchPad 单指单击 `KEYCODE_ENTER` / 镜腿单击广播 | 暂停 / 继续定时拍照；答案超过一页时改为**翻页** |
| **双击**（等同系统返回） | TouchPad 单指双击 `KEYCODE_BACK` | **返回**：关调试 → 清空结果 → 解除暂停 → **退出应用** |
| 单指前滑 / 后滑 | `DPAD_RIGHT→DPAD_DOWN` / `DPAD_LEFT→DPAD_UP` 键序列 | 切换 top-3 候选 |
| 长按 | TouchPad 长按 `KEYCODE_PROG_BLUE` + `ACTION_AI_START` | 调试页（索引规模 / OCR 与匹配耗时 / 频次 / 候选分数 / OCR 原文 / 当前题库 / 上传地址） |

调试页内：**单击** → 扫码换题库页，**双击 / 长按** → 回主界面。
扫码页内按任意键返回（`Done` / `Failed` 时单击可重新扫）。

## 开发验收口子

眼镜上没有 adb 能模拟的触摸/长按，`screencap` 在熄屏时还会返回全黑，所以留了几个
`am start` 参数直接用 intent 把界面摆到目标状态，再截图核对：

```bash
PKG=com.openqsy.glasses/.activities.main.MainActivity
adb shell input keyevent KEYCODE_WAKEUP                    # 不唤醒的话截图全黑
adb shell am start -n $PKG --es open debug                 # 调试页
adb shell am start -n $PKG --es open scan                  # 扫码换题库页
adb shell am start -n $PKG --es demo short|long|calc|ocr      # 灌一条假命中，验收答案区排版
adb shell am start -n $PKG --es demo steps --es apage 2       # 灌一条 4 页的应用题解答，直接落在第 3 页
adb shell am start -n $PKG --es url  http://…/qbank.json   # 跳过扫码，直测下载+编译
adb shell am start -n $PKG --es qrimg /data/local/tmp/qr.png  # 跳过相机，直接解一张二维码图
adb shell am start -n $PKG --es ocrimg /data/local/tmp/q3.png # 跳过相机，对一张图跑裁剪+识别+检索，只打日志
adb shell am start -n $PKG --es crop 0                        # 关掉「按取景框裁剪」，退回整帧识别，用于对比诊断
adb shell am start -n $PKG --es preview 1                     # 打开实时画面（默认只画辅助框，省电）
```

`tools/layout_preview.py` 可以在 PC 上把 480×640 的排布算一遍并渲染 PNG，做重叠/越界检查，
改字号或行高后先跑它，再上机截图。


## AI 兜底（可选）

题库里没有这道题时，可以让第三方大模型（任何 OpenAI 兼容接口）兜一次。**默认关闭**——不配置就是和以前一样的纯离线应用。

### 在哪配

眼镜上没有键盘，所以配置不在眼镜上做，用手机或电脑的浏览器打开眼镜的 HTTP 服务：

```bash
# 眼镜与手机/电脑连同一个 WiFi，先看眼镜 IP（也可以在调试页「换题库」那一行看到）
adb shell ip -4 addr show wlan0

# 浏览器打开
http://<眼镜IP>:8080/ai
```

眼镜没连 WiFi 时服务照样会起（绑的是 `0.0.0.0`），走 USB 也行：

```bash
adb forward tcp:18080 tcp:8080
# 浏览器打开 http://127.0.0.1:18080/ai
```

**打不开先看眼镜上有没有在跑这个应用**：HTTP 服务跟着应用进程活，应用被系统回收
（眼镜放着不用十几个小时就会被回收）、或者被 `am force-stop` 过，8080 就不再监听，
浏览器只会一直转圈。唤醒眼镜、打开应用即可恢复（`onCreate` 和 `onResume` 各起一次，
覆盖「先开应用后连 WiFi」）。确认方法：

```bash
adb shell netstat -ltn | grep 8080      # 有 LISTEN 才是活着
adb logcat -d | grep 上传服务            # 应看到「上传服务已启动 http://<IP>:8080」
```

### 页面上填什么

| 字段 | 说明 |
|---|---|
| baseUrl | 填到 `/v1` 为止，例如 `https://api.deepseek.com/v1` |
| API Key | 只存在眼镜本地（SharedPreferences），不传给任何第三方；留空 = 沿用上次保存的那份 |
| 模型 | 先点「拉取模型」从 `/models` 拉列表再选；也支持手填 |

接口按 OpenAI **Responses** 协议：`GET {baseUrl}/models` 拉列表、`POST {baseUrl}/responses` 提问
（`instructions` 放系统提示、`input` 放题目、`max_output_tokens` 限长、`store=false` 不留存）。

两种自动回退，所以填任何兼容接口都能用：

| 服务端情况 | 客户端行为 |
|---|---|
| 有 `/responses` | 走 Responses（默认路径） |
| 没有（404 / 405 / 501） | 退回 `POST {baseUrl}/chat/completions` |
| `/responses` 转发到 chat、回 chat 格式 | 按 `choices[0].message.content` 解析 |

随时可以点「关闭 AI（清空配置）」退回纯本地。

### 什么时候才会调 AI

- 题库**没找到**（最高分低于 `MIN_SCORE = 0.55`），或者找到了但**条件不符**（题目被改过）——只有这两种情况才问；
- 题库命中时**一律走本地答案**，一个包都不发（实测：喂一道能命中的题，假服务器日志零增长）；
- 节流：两次提问至少隔 15 秒（`AI_MIN_INTERVAL_MS`），认到的文字与上次完全相同则跳过——举着不动不会反复烧 token；
- 认到的文字不足 8 个字不问（`MIN_AI_QUESTION_CHARS`）；
- 答完显示「AI 答案」+ 模型名 + 耗时；之后又拍到题库里有的题，AI 结果会被本地答案顶掉。

> AI 这一路**需要联网**（要访问你填的第三方接口）；题库命中那一路仍然全程离线。

### 推理模型也能用

走 `/responses` 主干时不用做什么：同一个 `deepseek-v4.1-flash`，在 `chat/completions` 下会把
2000 额度全花在思考上（正文空、`finish_reason=length`），换成 `/responses` 后 3~4 秒就答完，
思考只有两百来个字符。

退回 `chat/completions` 时会带 `"thinking": {"type": "disabled"}` 把思考链关掉。不关的话，实测
`deepseek-v4-pro` 在「建筑面积」那道题上用了 5230~6573 个思考 token、**90~105 秒**才吐出正文，
2000 的 `max_tokens` 会被思考过程吃光（界面上的报错就是「模型没说完就被 max_tokens 截断了」）。
关掉之后同一道题 **2~7 秒**答完，正文还更简明。

服务商不认这个字段时会返回 400/422，此时自动去掉它重试一次——所以配非 DeepSeek 系的接口
也能正常工作，只是那种接口本来就大多不是推理型，不关思考也快。

## 已知边界

- 单绿屏：黑 = 不发光，UI 只能用绿色线条 + 文字，不能显示彩色题图；
- **定时拍照**：`ImageAnalysis` 持续取帧（`KEEP_ONLY_LATEST`），但每 **3 秒**才真正拍一张送 OCR，
  中间到的帧全部丢弃（`SHOT_INTERVAL_MS`）——屏幕上是「拍一张 → 出结果 → 定格 → 再拍」，
  而不是答案每几百毫秒抖一次；
- **取景框 = 识别范围**：面板顶部那块框就是每次送去 OCR 的范围，框里显示的也是同一块画面
  （同一次裁剪出来的，不会「看到这题、认出那题」）。
  画面**不走相机 `Preview` 那一路**，而是从识别流自己抽一张 5 fps 的缩略图（`OcrEngine.thumbnail`）。
  同一台机器各跑 60 秒实测（单核口径）：绑 `Preview` **52.9%**、只识别不显示画面 **23.5%**、
  缩略图方案 **37.4%**，温度基本不升。多花的十几个点是每 200ms 一次「Y 平面按行抽样 + Compose 重绘」，
  比「第二路出流 + TextureView 每帧合成」便宜一半。
  想看硬件直出的实时画面时加 `--es preview 1`（那种模式下 `Preview` 与 `ImageAnalysis`
  共用同一个 `ResolutionSelector`，比例不一致会让「框里看到的」和「实际识别的」错位，踩过）。
  一旦出答案**不收起框**，只从 140dp 压到 84dp（`ViewfinderCompactH`）：用户还得看得出相机
  对着哪一段，才能确认答案是这道题的；把整块移出组合会让 `PreviewView` detach，
  CameraX 去重配 surface 会撞 5000ms 超时、界面直接卡住（踩过，日志是 `Unable to configure camera` +
  `TimeoutException ... 5000 ms` + `Resetting Capture Session`）；
  框边每拍一张闪一下，标题栏同时写着「已拍 N 张」；
- **顶部优先**：一屏常常有两三道题，而框住的位置本身就是最强的线索——用户把要问的那道放在框的最上面。
  所以先把识别出来的行按「题号行」（`^\d{1,2}[.、．]`，常量 `Q_START`）切成一道一道，**从上往下试**，
  第一道过了阈值（0.55）就是答案，最多试 3 道（`MAX_TOP_BLOCKS`）；都不行才退回
  「以中心为锚滑动窗口」和「整段」两套兜底（`SolveViewModel.searchAroundCenter`）。
  实测一屏三题（y=0.26 / 0.44 / 0.61）：按分数会选中中间那道（0.978），按位置才选得对顶部那道（0.576）；
- **AI 回答期间不拍照**：把题发给 AI 之后，`aiBusy` 期间到帧直接丢；AI 回来才重新起算 3 秒。
  否则 AI 还在思考、镜头一动又拍一张，界面会在「AI 的结论」和「下一道题」之间来回跳；
- **改过的题不给答案**：题库再大也只有「见过的题」。同一个考点，数字被改过（26.800 → 36.800）、
  或者「属于」被改成「不属于」时，检索照样能命中原题——这时把原题答案端上去一定是错的，
  而用户看不出来。所以命中之后还有一道硬校验（`SolveViewModel.conditionConflict`）：
  先剔掉选项行、题号、「（1）」这类子问号，再比对数字集合与否定词集合，只要题目里出现原题没有的数字、
  或者比原题多了「不 / 不包括 / 除外 / 错误」这类词，就清空结果、显示「题干与题库不符（…）· 不给答案」。
  数字是单向比对（题目有、原题没有才算），认残的数字（`2018` 被 OCR 截成 `2`）按原题数字的前缀/后缀放过；
  「少了否定词」只在认到的文字覆盖原题题干 75% 以上时才敢下结论（`FULL_STEM_RATIO`）。
  实测：原题 0.791 正常出答案；同一道题把 26.800 改成 36.800 → 「数字 36.8 对不上」；
  把「多层建筑指」改成「不包括」→「多了「不包括」」；
- **不存照片**：相机帧只在内存里走一遍（`ImageProxy` → ML Kit → 文字），不转 Bitmap、不写文件、不上传，出口只有 `image.close()`；清单里刻意不申请 `WRITE_EXTERNAL_STORAGE` / `MANAGE_EXTERNAL_STORAGE` / 录音 / 录像权限——没有权限，就算哪天代码写错也落不了盘。实测：连续识别 45 秒（17 张），公共目录与私有目录文件数一字节不差；
- **AI 兜底默认关**：不填 baseUrl / API Key / 模型就完全不联网。填了之后也只有「题库没找到」或「条件不符」时才会请求（15 秒最多一次），题库命中一律本地出答案。配 AI 本身要求眼镜连着 WiFi——拉模型和提问都要访问你填的第三方接口；
- 同一题连续命中 2 次才标记「已确认」，避免答案随噪声乱跳；
- 答案里直接拼选项原文（`C. 中国共产党的…`，多选一行一个），不再只给字母；
- 长答案（简答/计算/案例解答、AI 给的应用题步骤）降为 16sp、每页 7 行、题干同时压到 1 行；
  超过一页时**单击翻页**，页码跟在「答案 ✓」或「模型 · 4331 毫秒」后面（如 `2/4`）。
  分页按**显示宽度**算（汉字 1、ASCII 0.5，每行 26 个单位），不会把 `合计 2400+9600 = 12000 ㎡`
  这种算式拦腰截断——按字数算就会截断；
- 两个热源：OCR 固定在 3 秒一次；预览是每秒 5 次的缩略图刷新（`THUMB_INTERVAL_MS = 200L`，
  调大就更省电、也更卡的）。`ImageAnalysis.Builder` **没有** `setTargetFrameRate`，写到那边会编译不过；
  再长时间使用仍建议单击暂停——暂停后连相机都解绑（不只是不识别），镜腿会明显凉下来，
  代价是恢复时要重新绑定、画面黑一两秒；
- ML Kit bundled 模型使 APK 增加约 25~35 MB；若目标固件缺少其 native 依赖，
  按 `docs/02-技术方案.md` 换 PP-OCR(ONNX Runtime) 实现，`OcrEngine` 接口不变。

## 文档

- `docs/01-官方裸机开发要点.md` —— 官方文档（custom.rokid.com 裸机开发专栏）要点与踩坑清单
- `docs/02-技术方案.md` —— 架构、OCR 选型对比、匹配算法、性能与功耗预算、里程碑
- `docs/03-题库索引格式.md` —— `qbank.idx` 二进制格式规范与实测数据
- `docs/04-换题库指南.md` —— **给不懂编程的使用者**：怎么把题库做成二维码让眼镜扫

> 仅用于个人学习与技术研究，请遵守考试与平台规则。

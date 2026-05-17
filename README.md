# 题眼 (Open-QSY) — 智能答题辅助工具

[![Python](https://img.shields.io/badge/Python-3.10%2B-blue)](https://www.python.org/)
[![PyQt6](https://img.shields.io/badge/GUI-PyQt6-green)](https://www.riverbankcomputing.com/software/pyqt/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](license.txt)

**题眼**是一款基于计算机视觉与大语言模型的智能答题辅助桌面应用。它能够实时捕获屏幕或摄像头画面，通过 OCR 识别题目内容，并在本地题库中快速匹配答案，同时支持调用 AI 进行智能解答。

---

## 功能特性

- **多源画面捕获**：支持摄像头实时采集与屏幕区域截图两种模式
- **三级 OCR 识别引擎**：本地 RapidOCR → Windows OCR → GPT-4o Vision 自动降级，确保识别稳定
- **智能题库匹配**：基于 rapidfuzz 模糊匹配与双字索引加速，支持万千级题库秒级检索
- **AI 智能作答**：集成 OpenAI API，在题库无匹配时自动给出答案与解析
- **悬浮结果展示**：边缘悬浮条 (EdgeBar) 与浮动面板 (FloatingPanel) 两种透明显示模式，可自定义颜色与透明度
- **丰富题库导入**：支持 `.xlsx`、`.xls`、`.csv`、`.docx` 格式，自动识别表头与列结构
- **全局热键**：可配置快捷键实现一键截图识别
- **结果缓存机制**：重复题目自动命中缓存，降低延迟与 API 消耗

---

## 项目结构

```
open-qsy/
├── core/                  # 核心引擎
│   ├── ocr_engine.py      # OCR 识别（RapidOCR / GPT Vision）
│   ├── matcher.py         # 题目匹配与结果聚合
│   ├── quiz_bank.py       # 题库索引、搜索与缓存
│   ├── vision_engine.py   # GPT-4o Vision API 封装
│   ├── camera.py          # 摄像头采集与 ROI 裁切
│   ├── hotkey_manager.py  # 全局热键监听
│   └── config.py          # 配置持久化
├── ui/                    # 用户界面
│   ├── main_window.py     # 主窗口（深色主题）
│   ├── camera_widget.py   # 摄像头预览组件
│   ├── screen_selector.py # 屏幕区域选择器
│   └── overlay.py         # 悬浮窗/边缘条组件
├── utils/                 # 工具模块
│   ├── file_parser.py     # 题库文件解析器
│   └── resources.py       # 资源路径管理
├── main.py                # 应用入口
├── requirements.txt       # Python 依赖
├── open-qsy.spec          # PyInstaller 打包配置
└── setup.iss              # Inno Setup 安装程序配置
```

---

## 安装与运行

### 环境要求

- Windows 10/11
- Python 3.10 或更高版本
- 摄像头（可选，用于摄像头模式）
- OpenAI API Key（可选，用于 AI 作答与 Vision 识别）

### 从源码运行

```bash
# 1. 克隆仓库
git clone <repo-url>
cd open-qsy

# 2. 创建虚拟环境
python -m venv venv
venv\Scripts\activate

# 3. 安装依赖
pip install -r requirements.txt

# 4. 启动应用
python main.py
```

### 打包为可执行文件

```bash
# 使用 PyInstaller 打包
pyinstaller open-qsy.spec

# 然后使用 Inno Setup 编译 setup.iss 生成安装程序
```

---

## 使用指南

### 1. 导入题库

- 点击主界面 **"导入题库"**，选择 Excel/Word/CSV 文件
- 系统会自动探测题目列与答案列；若探测失败，可手动指定
- 支持多次导入，题库自动合并

### 2. 配置识别源

- **摄像头模式**：选择摄像头设备并框选题目区域 (ROI)
- **屏幕模式**：点击 **"框选屏幕区域"** 划定识别范围，支持定时轮询或热键触发

### 3. 配置 API（可选）

- 在设置标签页填入 OpenAI API Key 与模型名称（默认 `gpt-4o`）
- 可自定义 Base URL，兼容第三方代理

### 4. 开始识别

- 点击 **"开始识别"** 或按下配置的全局热键
- 识别结果将显示在主界面右侧面板
- 开启悬浮窗后，结果会以透明悬浮条形式置顶显示在屏幕边缘

### 5. 悬浮窗自定义

- 右键悬浮条可展开完整浮动面板
- 支持拖拽调整大小、自定义背景颜色与透明度
- 所有设置自动保存至 `~/.open-qsy/config.json`

---

## 配置说明

配置文件位于用户目录下 `~/.open-qsy/config.json`：

| 键 | 说明 | 默认值 |
|---|---|---|
| `base_url` | OpenAI API 地址 | `https://api.openai.com/v1` |
| `api_key` | API 密钥 | `""` |
| `model` | 模型名称 | `gpt-4o` |
| `camera_index` | 摄像头索引 | `0` |
| `roi` | 摄像头 ROI 区域 | `null` |
| `frame_interval` | 屏幕轮询间隔（秒） | `1.0` |
| `match_threshold` | 题库匹配阈值 | `70` |
| `enable_ai_answer` | 启用 AI 作答 | `true` |
| `use_local_ocr` | 优先本地 OCR | `true` |
| `overlay_mode` | 悬浮显示模式 | `"none"` |
| `overlay_bg_color` | 悬浮窗背景色 | `"#0f0f23"` |
| `overlay_bg_alpha` | 悬浮窗透明度 | `220` |

---

## 依赖清单

| 包 | 用途 |
|---|---|
| PyQt6 | 桌面 GUI 框架 |
| opencv-python | 图像采集与处理 |
| openai | GPT Vision / Chat API |
| rapidocr-onnxruntime | 本地 OCR 引擎 |
| rapidfuzz | 高速模糊字符串匹配 |
| pandas / openpyxl | Excel/CSV 解析 |
| numpy | 数值计算 |
| python-docx | Word 文档解析 |

完整依赖见 [`requirements.txt`](requirements.txt)。

---

## 贡献者

- **孙启然** — 架构设计与核心引擎开发
- **杨一旭** — UI/UX 开发与打包发布

---

## 许可证

本项目基于 [MIT License](license.txt) 开源。

---

> **提示**：题眼仅供学习与技术研究使用。请遵守相关考试规定与平台用户协议，切勿用于违规作弊场景。

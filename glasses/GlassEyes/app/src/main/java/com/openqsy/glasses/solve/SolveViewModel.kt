package com.openqsy.glasses.solve

import android.app.Application
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openqsy.glasses.ai.AiClient
import com.openqsy.glasses.ai.AiConfig
import com.openqsy.glasses.live.LiveFrameAnalyzer
import com.openqsy.glasses.ocr.MlKitOcrEngine
import com.openqsy.glasses.ocr.OcrEngine
import com.openqsy.glasses.ocr.OcrOutcome
import com.openqsy.glasses.quiz.BankDownloader
import com.openqsy.glasses.quiz.QBankLoader
import com.openqsy.glasses.quiz.QBankStore
import com.openqsy.glasses.quiz.QuizHit
import com.openqsy.glasses.scan.QrCodeScanner
import com.openqsy.glasses.transfer.QBankUploadServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** 眼镜端 UI 状态机（定时拍照识别）。 */
sealed interface QuizUiState {
    data object LoadingIndex : QuizUiState

    /**
     * 实时扫描中。
     * - [hit] 为 null 表示还没扫到足够可信的题；
     * - [locked] 表示连续多帧命中同一题，结果已稳定，可放心读。
     */
    data class Live(
        val hit: QuizHit?,
        val locked: Boolean,
        val candidateIndex: Int,
        val candidateCount: Int,
        val ocrMs: Long,
        val matchMs: Long,
        val scansPerMin: Int,
        val paused: Boolean,
        val hint: String,
        /** 最近一次识别到的原文摘要（让用户看见"到底识别到了什么"）。 */
        val ocrPreview: String = "",
    ) : QuizUiState

    data class Failure(val message: String) : QuizUiState
}

/**
 * 题库没匹配上时，「问 AI」这一路的状态。
 *
 * 只在题库没命中时才出现：题库命中永远走本地答案，不联网、也没有等待。
 */
sealed interface AiState {
    /** 没配置 AI，或这一屏还轮不到 AI。 */
    data object Off : AiState

    /** 已经把题目发出去了，正在等模型。 */
    data class Thinking(val model: String) : AiState

    /** 拿到答案。[elapsedMs] 只是让用户心里有数（等了几秒）。 */
    data class Done(val answer: String, val model: String, val elapsedMs: Long) : AiState

    /** 失败了，[message] 直接展示给用户。 */
    data class Failed(val message: String) : AiState
}

/** 调试页信息（长按切换）。 */
data class DebugInfo(
    val engine: String = "-",
    val indexStats: String = "-",
    val ocrChars: Int = 0,
    val ocrMs: Long = 0,
    val matchMs: Long = 0,
    val topScores: String = "-",
    val ocrPreview: String = "",
    val scansPerMin: Int = 0,
    val throttledSkips: Int = 0,
    val indexSelfCheck: String = "-",
    /** 当前题库来自哪里：内置 / Download 里的某个文件 / 上传。 */
    val bankSource: String = "-",
    /** 换题库页上显示的一行简介：题库名 + 题数。 */
    val bankBrief: String = "",
    /** 手机浏览器访问这个地址即可换题库；未连 WiFi 时为空。 */
    val uploadUrl: String = "",
    val error: String? = null,
)

/**
 * 扫码换题库页的状态机。
 *
 * 一条链路：扫到二维码 → 取到 URL → 下载 → 设备端编译 → 热替换。
 * 每一步都可能失败，所以每一步单独一个状态，界面上永远能看出「卡在哪」。
 */
sealed interface ScanState {
    /** 还没进这一页。 */
    data object Idle : ScanState

    /** 正在找二维码。[fps] 只是让人看出相机确实在工作，免得对着空镜头干等。 */
    data class Scanning(val fps: Int) : ScanState

    /** 已识别出地址，马上开始下载。 */
    data class Found(val url: String) : ScanState

    /** [total] 为 -1 表示服务端没给 Content-Length。 */
    data class Downloading(val url: String, val received: Long, val total: Long) : ScanState

    /** 设备端把 json 编译成 idx（1.9 MB 约 3 秒）。 */
    data class Compiling(val url: String, val bytes: Int) : ScanState

    data class Done(
        val title: String,
        val questions: Int,
        val byteSize: Int,
        val elapsedMs: Long,
    ) : ScanState

    data class Failed(val message: String) : ScanState
}

/**
 * 定时拍照识题：**每 [SHOT_INTERVAL_MS] 拍一张**，拍完就把结果定格，用户不用按任何键。
 *
 * 为什么不是「连续识别」：
 * 早先是「画面一变就 350 ms 重认一次」，屏幕上的答案每几百毫秒抖一次，
 * 用户既看不清、也不知道相机到底在看哪儿。改成固定节拍后语义变成相机：
 * 拍一张 → 出结果 → 静止 3 秒 → 再拍一张，屏幕上是稳的。
 *
 * 三层控制：
 * 1. **固定节拍**：两次拍摄之间到的帧直接丢弃（`ImageAnalysis` 只保留最新帧，不积压）。
 * 2. **串行 + 看门狗**：见 [LiveFrameAnalyzer]，同一时刻只跑一次 OCR。
 * 3. **防抖锁定**：同一 docId 在最近 [VOTE_WINDOW] 次拍摄里出现 ≥ [LOCK_VOTES] 次才标记
 *    `locked`，避免某一张拍糊了答案就跳到别的题。
 *
 * 交互：
 * - 单击：暂停 / 继续拍照（想停下来读答案时用）
 * - 双击：返回
 * - 前滑 / 后滑：在 top-3 候选里切换
 * - 长按：调试页
 */
class SolveViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<QuizUiState>(QuizUiState.LoadingIndex)
    val state: StateFlow<QuizUiState> = _state.asStateFlow()

    private val _debug = MutableStateFlow(DebugInfo(engine = ENGINE_NAME))
    val debug: StateFlow<DebugInfo> = _debug.asStateFlow()

    private val engine: OcrEngine = MlKitOcrEngine()

    /** 分析线程会读，热切换时主线程会写，必须 volatile。 */
    @Volatile
    private var bank: QBankStore? = null

    @Volatile
    private var indexReady = false

    /** 题库装载器：负责自动发现本地题库文件、编译 json、写上传落点。 */
    private val loader by lazy { QBankLoader(getApplication()) }

    /** 局域网题库上传服务；只在连上 WiFi 时启动。 */
    private var uploadServer: QBankUploadServer? = null

    /** AI 接入配置；三项齐全才可用，否则整条 AI 链路都不启用（保持全程离线）。 */
    @Volatile private var aiConfig = AiConfig()

    /** 上次真正发起 AI 调用的时刻，用来节流。 */
    private var lastAiAt = 0L

    /** 上次问过的正文（去空白形式）：同一张卷子反复拍到同一段文字时不重复烧 token。 */
    private var lastAiQuestion = ""

    private val _ai = MutableStateFlow<AiState>(AiState.Off)

    /** 题库没匹配上时 AI 这一路的状态，界面直接订阅。 */
    val ai: StateFlow<AiState> = _ai.asStateFlow()

    /** 当前题库的名字与体积，供手机上传页展示。 */
    @Volatile
    private var currentTitle = ""
    @Volatile
    private var currentBytes = 0
    @Volatile
    private var currentSource = "-"

    /** 交给 CameraX 的实时分析器。 */
    val analyzer: ImageAnalysis.Analyzer = LiveFrameAnalyzer(viewModelScope) { image ->
        handleFrame(image)
    }

    // ---- 扫码换题库 ----

    private val _scan = MutableStateFlow<ScanState>(ScanState.Idle)
    val scan: StateFlow<ScanState> = _scan.asStateFlow()

    /** 扫码分析器。每次进页面都换一个新的，因为旧的拿到结果后已置 stopped。 */
    @Volatile
    private var scanAnalyzerRef: ImageAnalysis.Analyzer = newScanAnalyzer()

    val scanAnalyzer: ImageAnalysis.Analyzer get() = scanAnalyzerRef

    /** 一次只处理一个扫码任务：连点、重复识别都不该并发下载。 */
    @Volatile
    private var scanBusy = false

    private val scanFrameTimes = ArrayDeque<Long>()
    private var lastScanEmit = 0L

    // ---- 定时拍照状态 ----
    @Volatile
    private var paused = false

    /** 上一次真正拍下并送进 OCR 的时刻；两次拍摄之间到的帧全部丢弃。 */
    private var lastShotAt = 0L

    /**
     * 取景框宽高比（宽/高），由界面实测上报。
     *
     * 0 表示还没量到——那时按整帧识别。量到之后只认「框里能看到的那块」，
     * 屏幕上看到的和实际识别的就是同一块画面。
     */
    @Volatile
    private var viewfinderAspect = 0f

    /**
     * 快门计数：每真正拍下一张 +1。
     *
     * 界面拿它做「闪光」——用户要的是"拍一张 → 出结果 → 过几秒再拍"，
     * 没有这个信号时，画面看上去是静止的，用户会以为根本没在拍。
     */
    private val _shot = MutableStateFlow(0)
    val shot: StateFlow<Int> = _shot.asStateFlow()

    /** 两次拍摄之间丢掉了多少帧，只给调试页看。 */
    private var throttledSkips = 0

    // ---- 结果与防抖 ----
    private var candidates: List<QuizHit> = emptyList()
    private var currentCandidate = 0
    private var locked = false
    private val voteWindow = ArrayDeque<Int>()
    private val scanTimes = ArrayDeque<Long>()

    private var lastOcrMs = 0L
    private var lastMatchMs = 0L
    private var lastOcrChars = 0
    private var lastTopScores = "-"

    /** 本次命中用的那段文字（中心窗口或整段），条件是「题被改过」校验的比对基准。 */
    @Volatile
    private var lastMatchText = ""
    private var lastOcrPreview = ""
    private var lastError: String? = null
    private var selfCheckText = "-"

    init {
        aiConfig = AiConfig.load(getApplication())
        Log.i(TAG, "AI 配置：${aiConfig.describe()}")
        viewModelScope.launch(Dispatchers.IO) { loadIndex() }
    }

    // ------------------------------------------------------------------ 索引

    private fun loadIndex() {
        val t0 = SystemClock.elapsedRealtime()
        val loaded = runCatching { loader.loadBest() }
            .onFailure { Log.e(TAG, "题库加载失败", it) }
            .getOrNull()
        if (loaded == null) {
            _state.value = QuizUiState.Failure("题库加载失败：设备里没有可用的题库文件")
            _debug.update { it.copy(error = "index load failed") }
            return
        }
        applyLoaded(loaded, SystemClock.elapsedRealtime() - t0)
    }

    /**
     * 把一份装载好的题库设为当前题库（启动加载和上传热切换共用）。
     *
     * 切换时必须清空候选与投票窗口：旧题库的 docId 在新题库里指向完全不同的题，
     * 不清空会拿新索引去显示旧答案。
     */
    private fun applyLoaded(loaded: QBankLoader.Loaded, elapsedMs: Long) {
        bank = loaded.store
        candidates = emptyList()
        currentCandidate = 0
        voteWindow.clear()
        locked = false
        lastShotAt = 0L
        currentTitle = loaded.title
        currentBytes = loaded.byteSize
        currentSource = loaded.source.label
        indexReady = true

        // 启动自检：确认「索引 + 归一化 + 查找」整条链路真的通，
        // 而不是等用户举着眼镜拍半天才发现永远匹配不上。
        selfCheckText = runCatching { loaded.store.selfCheck() }
            .map { r ->
                if (r.ok) {
                    "通过 · top1=#" + r.topDocId + " @" + "%.2f".format(r.topScore)
                } else {
                    "失败 · " + r.message
                }
            }
            .getOrElse { "异常 · " + (it.message ?: it.javaClass.simpleName) }

        val loadNote = if (loaded.compileMs > 0) " · 编译 ${loaded.compileMs}ms" else ""
        _debug.update { it.copy(
            indexStats = "${loaded.store.statsText()} · ${loaded.source.label} · " +
                "载入 ${elapsedMs}ms$loadNote",
            indexSelfCheck = selfCheckText,
            bankSource = "${loaded.source.label}（${loaded.describe()}）",
            bankBrief = "${loaded.title.ifBlank { loaded.source.label }} · ${loaded.questionCount} 题",
            error = null,
        ) }
        emitLive(hint = "对准题目 · 每 3 秒自动拍一张")
    }

    /**
     * 重新装载题库 —— 上传页传完文件后调用，不用重启应用。
     *
     * 编译 json 可能要十几秒，所以整段放在 IO 线程，完成后回主线程切状态。
     */
    fun reloadBank() {
        viewModelScope.launch(Dispatchers.IO) {
            val t0 = SystemClock.elapsedRealtime()
            runCatching { loader.loadBest() }
                .onSuccess { loaded ->
                    withContext(Dispatchers.Main) {
                        applyLoaded(loaded, SystemClock.elapsedRealtime() - t0)
                    }
                }
                .onFailure { t ->
                    Log.e(TAG, "重载题库失败", t)
                    withContext(Dispatchers.Main) {
                        _debug.update { it.copy(error = "重载失败：${t.message}") }
                    }
                }
        }
    }

    // ------------------------------------------------------------------ 上传

    /**
     * 启动局域网题库上传服务。
     *
     * 服务绑的是 0.0.0.0，本来就不依赖有没有局域网 IP，所以拿不到 IP 也照样启动：
     * 没 WiFi 时用 USB 转发（`adb forward tcp:18080 tcp:8080`）也能在电脑上打开配置页。
     * 服务只监听内网，不访问公网；应用退出时在 [onCleared] 里关闭。
     */
    fun startUploadServer() {
        if (uploadServer?.isRunning == true) return
        val ip = QBankUploadServer.localIpv4()
        val server = QBankUploadServer(
            statusProvider = { uploadStatusJson() },
            onUpload = { fileName, bytes ->
                runCatching {
                    val loaded = loader.acceptUpload(fileName, bytes)
                    viewModelScope.launch(Dispatchers.Main) { applyLoaded(loaded, 0L) }
                    QBankUploadServer.UploadOutcome.Ok(
                        message = "已启用 ${loaded.questionCount} 题",
                        detail = loaded.describe(),
                    )
                }.getOrElse { t ->
                    QBankUploadServer.UploadOutcome.Err(t.message ?: "题库处理失败")
                }
            },
            aiConfigProvider = { aiConfig },
            onAiConfigSave = { cfg ->
                AiConfig.save(getApplication(), cfg)
                viewModelScope.launch(Dispatchers.Main) { reloadAiConfig() }
            },
        )
        runCatching { server.start() }
            .onSuccess {
                uploadServer = server
                _debug.update {
                    it.copy(uploadUrl = if (ip != null) server.url() else "USB 127.0.0.1:8080")
                }
            }
            .onFailure { t ->
                Log.w(TAG, "上传服务启动失败", t)
                _debug.update { it.copy(uploadUrl = "启动失败：${t.message}") }
            }
    }

    private fun uploadStatusJson(): String {
        val b = bank
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        return if (b == null) {
            """{"ok":false,"title":"","questions":0,"terms":0,"bytes":0,"source":"未加载","selfCheck":"-"}"""
        } else {
            val title = currentTitle.ifBlank { currentSource }
            """{"ok":true,"title":"${esc(title)}","questions":${b.docCount},""" +
                """"terms":${b.termCount},"bytes":$currentBytes,""" +
                """"source":"${esc(currentSource)}","selfCheck":"${esc(selfCheckText)}"}"""
        }
    }

    // ------------------------------------------------------------------ 相机

    fun onCameraReady() {
        _debug.update { it.copy(error = null) }
    }

    fun onCameraError(message: String) {
        _state.value = QuizUiState.Failure("相机：$message")
        _debug.update { it.copy(error = "camera: $message") }
    }

    /**
     * 界面把取景框的实际宽高报上来。
     *
     * 取景框是 480 宽的横条，相机帧是竖构图，`FILL_CENTER` 只能露出整帧中间的一条。
     * 只有拿到这个比例，才能把「框里看到的那块」原样裁出来送 OCR，
     * 否则框外的相邻题目也会被认进来，答案就会跳到别的题上。
     */
    fun setViewfinderAspect(aspect: Float) {
        if (aspect > 0f && aspect.isFinite()) viewfinderAspect = aspect
    }

    // ------------------------------------------------------------------ 主循环

    /**
     * 处理一帧。**帧不落盘**：
     *
     * 帧只在 [ImageProxy] 的 native 缓冲里存在，交给 [engine] 做 OCR，
     * 取到的只有文字；无论成功、失败还是被节拍丢弃，出口都是 `image.close()`。
     * 这里没有 Bitmap、没有 FileOutputStream、没有 cacheDir，也没有任何上传。
     * 配合 AndroidManifest 里刻意不申请写存储权限，即使将来写错也写不出去。
     */
    private suspend fun handleFrame(image: ImageProxy) {
        val store = bank
        if (store == null || !indexReady) {
            image.close()
            return
        }
        if (paused) {
            image.close()
            return
        }

        val now = SystemClock.elapsedRealtime()

        // 固定节奏：每 [SHOT_INTERVAL_MS] 拍一张。中间到的帧直接丢弃。
        // 这样用户看到的是「拍一张 → 出结果 → 定格几秒 → 再拍一张」，
        // 而不是画面不停地跳、答案一直在变。
        if (now - lastShotAt < SHOT_INTERVAL_MS) {
            throttledSkips++
            image.close()
            return
        }
        lastShotAt = now
        _shot.value = _shot.value + 1

        // 3) OCR（实现内部负责 close(image)）
        val ocr = try {
            engine.recognize(image, viewfinderAspect)
        } catch (t: Throwable) {
            Log.e(TAG, "OCR 异常", t)
            runCatching { image.close() }
            lastError = t.message ?: t.javaClass.simpleName
            return
        }
        lastOcrMs = ocr.elapsedMs
        lastOcrChars = ocr.text.length
        // 把认到的原文打出来：屏幕上只显示前 160 字，日志里留全文方便比对"认错了什么"。
        Log.d(
            TAG,
            "OCR ${ocr.text.length}字/${ocr.elapsedMs}ms aspect=${viewfinderAspect}: " +
                ocr.text.replace('\n', ' ').take(300),
        )

        if (!ocr.ok) {
            lastError = ocr.error
            // 没识别到内容：通常是没对准/太远，不清空已有结果，避免屏幕闪
            emitLive(hint = if (ocr.error != null) "识别异常：${ocr.error}" else "镜头朝题目 · 3 秒后重拍")
            return
        }
        lastError = null
        lastOcrPreview = ocr.text.replace('\n', ' ').take(160)

        // 4) 匹配
        val t0 = SystemClock.elapsedRealtime()
        val hits = searchAroundCenter(store, ocr)
        lastMatchMs = SystemClock.elapsedRealtime() - t0
        lastTopScores = hits.take(3).joinToString(" / ") { "%.2f".format(it.score) }
        Log.d(TAG, "match ${lastMatchMs}ms top=${lastTopScores}")

        val confident = hits.filter { it.score >= MIN_SCORE }.take(MAX_CANDIDATES)
        val best = confident.firstOrNull()

        if (best == null) {
            // 没到阈值：这张照片没认出题库里的题 —— 这正是 AI 唯一该出场的时候
            emitLive(hint = "题库里没找到 · 识别 ${lastOcrChars} 字")
            maybeAskAi(ocr.text)
            return
        }

        // 题库命中了：AI 那一路就没意义了，把上一条 AI 结果收走，免得界面还挂着旧答案
        if (_ai.value != AiState.Off) _ai.value = AiState.Off

        // 4.5) 条件硬校验：题干里的数字、否定词跟题库原题对不上，多半是「同一考点但改了条件」。
        // 这时把原题的答案端上去一定是错的，而且用户看不出来——宁可不给。
        val conflict = conditionConflict(lastMatchText, best.stem)
        if (conflict != null) {
            Log.d(
                TAG,
                "条件不符（$conflict）q=${lastMatchText.replace('\n', ' ').take(80)} " +
                    "| doc=${best.stem.replace('\n', ' ').take(80)}",
            )
            candidates = emptyList()
            currentCandidate = 0
            voteWindow.clear()
            locked = false
            emitLive(hint = "题干与题库不符（$conflict）· 不给答案")
            // 题库答不了这道题（题被改过），正好交给 AI
            maybeAskAi(ocr.text)
            return
        }

        // 5) 防抖投票
        voteWindow.addLast(best.docId)
        while (voteWindow.size > VOTE_WINDOW) voteWindow.removeFirst()
        val votes = voteWindow.count { it == best.docId }
        locked = votes >= LOCK_VOTES

        val sameAsBefore = candidates.firstOrNull()?.docId == best.docId
        candidates = confident
        // 结果换了就回到第 1 候选；同一题则保留用户手动翻到的位置
        if (!sameAsBefore) currentCandidate = 0
        currentCandidate = currentCandidate.coerceIn(0, candidates.lastIndex)

        emitLive(hint = if (locked) "已确认 · 3 秒后重拍" else "已认出 · 3 秒后重拍")
    }

    /**
     * 从「一屏好几道题」里挑出用户正对着的那一道。
     *
     * 眼镜一屏能装两三道题，整段 OCR 文本拿去检索时，命中的往往是文字最密的那道，
     * 不一定是用户框住的那道——这就是"识别出来的和实际内容完全不一样"的来源。
     *
     * 做法：从最靠近画面中心的一行出发，向上下交替扩展，取 2..[MAX_WINDOW_LINES] 行
     * 分别检索，挑分数最高的窗口。总共只多 5 次检索（每次几毫秒），代价可忽略。
     * 窗口都认不出来时退回整段，避免长题干被切碎反而认不出。
     */
    private fun searchAroundCenter(store: QBankStore, ocr: OcrOutcome): List<QuizHit> {
        val lines = ocr.lines
        if (lines.size < 3) {
            lastMatchText = ocr.text
            return store.search(ocr.text, topK = SEARCH_TOPK)
        }

        val sorted = lines.sortedBy { it.centerY }
        val centerIdx = sorted.indices.minByOrNull { abs(sorted[it].centerY - 0.5f) } ?: 0

        // 「由近及远」的行序：中心那行、下一行、上一行、下二行、上二行……
        val order = ArrayList<Int>(sorted.size)
        order.add(centerIdx)
        var step = 1
        while (order.size < sorted.size) {
            val up = centerIdx - step
            val dn = centerIdx + step
            if (up >= 0) order.add(up)
            if (dn < sorted.size) order.add(dn)
            step++
        }

        var bestHits: List<QuizHit> = emptyList()
        var bestScore = 0f
        var bestLines = 0
        var bestText = ""
        for (n in 2..minOf(MAX_WINDOW_LINES, order.size)) {
            val picked = order.take(n).sorted()
            val text = picked.joinToString("\n") { sorted[it].text }
            // 太短的窗口容易"因为撞上题干里的几个字"拿到虚高分，直接跳过。
            if (text.length < MIN_WINDOW_CHARS) continue
            val hits = store.search(text, topK = SEARCH_TOPK)
            val top = hits.firstOrNull()?.score ?: 0f
            if (top > bestScore) {
                bestScore = top
                bestHits = hits
                bestLines = n
                bestText = text
            }
        }

        val fallback = store.search(ocr.text, topK = SEARCH_TOPK)
        val fallbackScore = fallback.firstOrNull()?.score ?: 0f
        // 注意：整串要先拼好再 format。写成 "a=%.2f " + "b=%.3f".format(...) 的话，
        // 因为 . 的优先级高于 +，只有后半段会被替换，前半段会原样打出 "%.2f"。
        val msg = "center: lines=${lines.size} centerY=%.2f win=${bestLines}行 " +
            "score=%.3f full=%.3f"
        Log.d(
            TAG,
            msg.format(sorted[centerIdx].centerY, bestScore, fallbackScore),
        )

        // 窗口没把握时用整段；整段也没更好就还是窗口（窗口至少更"聚焦"）。
        val useFallback = bestScore <= 0f || fallbackScore > bestScore + 0.05f
        lastMatchText = if (useFallback) ocr.text else bestText
        return if (useFallback) fallback else bestHits
    }

    /**
     * 比对「本次认到的文字」和「题库原题题干」里的硬条件。
     *
     * 只查两类能一票否决的东西：
     * - 数字：题目里出现了题库原题没有的数字（改了金额、年限、利率、面积）；
     * - 否定词：题目比原题多（或少）了「不 / 非 / 除外 / 错误」这类词
     *   ——「属于」被改成「不属于」，答案正好相反。
     *
     * 方向基本是单向的：只报「题目有、原题没有」。反方向多半是 OCR 只截到了半句题干，
     * 报出来全是误报。唯一例外是「少了否定词」：原题带否定词、而这一窗口几乎覆盖了整题，
     * 那说明题目真被改过（用 [FULL_STEM_RATIO] 判断"几乎覆盖"）。
     *
     * @return null 表示没问题；否则返回一句给用户看的说明。
     */
    private fun conditionConflict(query: String, doc: String): String? {
        if (query.isBlank() || doc.isBlank()) return null

        // 选项行（"A. 12 万元"）里的数字不是题干的数字，先剔掉；题号、子问号同理。
        val body = query.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !OPTION_LINE.matches(it) }
            .map { RE_LEADING_NO.replace(RE_SUBQ_NO.replace(it, ""), "") }
            .joinToString("\n")
        if (body.isBlank()) return null

        // 否定词先查：它比数字可靠得多（OCR 掉个数字很常见，掉一个「不」罕见），
        // 而且「属于 / 不属于」这类改动会直接把答案翻转。
        val qNeg = negationTokens(body)
        val dNeg = negationTokens(doc)

        val qOnly = qNeg - dNeg
        if (qOnly.isNotEmpty()) return "多了「${qOnly.sorted().joinToString("")}」"

        val qLen = body.count { !it.isWhitespace() }
        val dLen = doc.count { !it.isWhitespace() }
        val covered = dLen > 0 && qLen >= dLen * FULL_STEM_RATIO
        val dOnly = dNeg - qNeg
        if (covered && dOnly.isNotEmpty()) return "少了「${dOnly.sorted().joinToString("")}」"

        val qNums = numberTokens(body)
        val dNums = numberTokens(doc)
        if (qNums.isNotEmpty() && dNums.isNotEmpty()) {
            // 认残的数字（"2018" 被截成 "2"）是原题数字的前缀/后缀，不算新增
            val extra = qNums.filter { q ->
                dNums.none { d -> d == q || d.startsWith(q) || d.endsWith(q) }
            }
            if (extra.isNotEmpty()) return "数字 ${extra.sorted().take(3).joinToString("、")} 对不上"
        }

        return null
    }

    /** 题干里的数字集合；`12.50` 与 `12.5` 视为同一个数，`12000` 不受尾零影响。 */
    private fun numberTokens(text: String): Set<String> =
        NUMBER.findAll(text).map { m ->
            val raw = m.value
            if (raw.contains('.')) raw.trimEnd('0').trimEnd('.') else raw
        }.filter { it.isNotEmpty() }.toSet()

    private fun negationTokens(text: String): Set<String> = NEGATION.findAll(text).map { it.value }.toSet()

    // ------------------------------------------------------------------ AI

    /**
     * 题库没答案时才走这里。
     *
     * 三道闸门，都是为了让眼镜别每 3 秒就往模型上打一枪：
     * - 没配好 AI → 直接返回（等于全程离线）；
     * - 距上次调用不到 [AI_MIN_INTERVAL_MS] → 跳过；
     * - 认到的文字和上次问过的完全一样 → 跳过（举着不动就别重复烧 token）。
     */
    private fun maybeAskAi(text: String) {
        val cfg = aiConfig
        if (!cfg.enabled) return
        if (text.count { !it.isWhitespace() } < MIN_AI_QUESTION_CHARS) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAiAt < AI_MIN_INTERVAL_MS) return
        val norm = text.filter { !it.isWhitespace() }
        if (norm == lastAiQuestion) return
        lastAiAt = now
        lastAiQuestion = norm
        askAi(text)
    }

    private fun askAi(text: String) {
        val cfg = aiConfig
        _ai.value = AiState.Thinking(cfg.model)
        viewModelScope.launch {
            val t0 = SystemClock.elapsedRealtime()
            val result = withContext(Dispatchers.IO) { runCatching { AiClient.ask(cfg, text) } }
            val ms = SystemClock.elapsedRealtime() - t0
            result.onSuccess { answer ->
                Log.i(TAG, "AI 作答 ${answer.length}字/${ms}ms：${answer.take(60).replace('\n', ' ')}")
                _ai.value = AiState.Done(answer, cfg.model, ms)
            }.onFailure { e ->
                Log.w(TAG, "AI 调用失败", e)
                _ai.value = AiState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** AI 配置在手机上保存之后调一次，让新配置立刻生效（不用重启应用）。 */
    fun reloadAiConfig() {
        aiConfig = AiConfig.load(getApplication())
        lastAiAt = 0L
        lastAiQuestion = ""
        _ai.value = AiState.Off
        Log.i(TAG, "AI 配置已刷新：${aiConfig.describe()}")
    }

    private fun emitLive(hint: String) {
        val shown = candidates.getOrNull(currentCandidate)
        recordScan()
        // 必须用 copy：这是每帧都会走的热路径，若在这里新建 DebugInfo，
        // 题库名和上传地址会被每帧重新清零（曾经就踩过，表现为"题库"恒为 -）。
        _debug.update {
            it.copy(
                engine = engine.name,
                ocrChars = lastOcrChars,
                ocrMs = lastOcrMs,
                matchMs = lastMatchMs,
                topScores = lastTopScores,
                ocrPreview = lastOcrPreview,
                scansPerMin = scansPerMin(),
                throttledSkips = throttledSkips,
                indexSelfCheck = selfCheckText,
                error = lastError,
            )
        }
        _state.value = QuizUiState.Live(
            hit = shown,
            locked = locked && shown != null,
            candidateIndex = currentCandidate,
            candidateCount = candidates.size,
            ocrMs = lastOcrMs,
            matchMs = lastMatchMs,
            scansPerMin = scansPerMin(),
            paused = paused,
            hint = hint,
            ocrPreview = lastOcrPreview,
        )
    }

    // ------------------------------------------------------------------ 交互

    /** 单击：暂停 / 继续实时识别。 */
    fun togglePause() {
        paused = !paused
        if (paused) {
            emitLive(hint = "已暂停 · 单击继续")
        } else {
            // 恢复时立刻拍一张，不用让用户干等一个周期
            lastShotAt = 0L
            emitLive(hint = "继续拍照…")
        }
    }

    /** 前滑 / 后滑：在 top-3 候选间切换。 */
    fun cycleCandidate(forward: Boolean = true) {
        val n = candidates.size
        if (n <= 1) return
        currentCandidate = if (forward) (currentCandidate + 1) % n else (currentCandidate - 1 + n) % n
        emitLive(hint = "候选 ${currentCandidate + 1}/$n")
    }

    /** 回到「无结果」的干净状态（清空投票，重新开始找）。 */
    fun reset() {
        candidates = emptyList()
        currentCandidate = 0
        voteWindow.clear()
        locked = false
        lastShotAt = 0L
        emitLive(hint = "已清空 · 继续扫描")
    }

    // ------------------------------------------------------------------ 工具

    private fun recordScan() {
        val now = SystemClock.elapsedRealtime()
        scanTimes.addLast(now)
        while (scanTimes.isNotEmpty() && now - scanTimes.first() > 60_000) {
            scanTimes.removeFirst()
        }
    }

    private fun scansPerMin(): Int = scanTimes.size

    // ------------------------------------------------------------ 扫码换题库

    /**
     * 进入扫码页：重置状态、换一个新的识别器。
     *
     * 必须换新的——上一个在扫到结果时会把自己置成 stopped，复用就再也扫不到第二张。
     */
    fun beginScan() {
        // 正在下载/编译时不许重置，否则会把进度冲掉（进页面与自动触发可能撞在一起）
        if (scanBusy) return
        (scanAnalyzerRef as? QrCodeScanner)?.stop()
        synchronized(scanFrameTimes) { scanFrameTimes.clear() }
        lastScanEmit = 0L
        scanAnalyzerRef = newScanAnalyzer()
        _scan.value = ScanState.Scanning(0)
    }

    /**
     * 跳过扫码，直接按地址换题库。
     *
     * 是给 `adb shell am start ... --es url <地址>` 用的验收口子：
     * 真机上"举着眼镜扫电脑屏幕"这一步没法自动做，但下载/编译/热替换可以这样单独验。
     */
    fun scanFromUrl(url: String) {
        if (scanBusy) return
        (scanAnalyzerRef as? QrCodeScanner)?.stop()
        onScannedUrl(url)
    }

    /**
     * 验收口子：对一张图片文件跑完整的「裁剪 → 识别 → 中心窗口检索」，只打日志不动界面。
     *
     * 用法 `--es ocrimg /data/local/tmp/q.png`。存在意义：真机上没法远程让相机对准
     * 某张纸，排版与匹配的回归只能靠固定图片来跑。
     */
    fun ocrFromImage(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val bmp = runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
            if (bmp == null) {
                Log.w(TAG, "ocrimg: 解不出图片 $path")
                return@launch
            }
            val ml = engine as? MlKitOcrEngine
            if (ml == null) {
                Log.w(TAG, "ocrimg: 当前引擎不支持位图入口")
                bmp.recycle()
                return@launch
            }
            // 离屏验收时默认认整帧：取景框比例来自界面实测，没界面时按 0 处理就是不裁。
            // 要复现相机那条裁剪路径，先用相机跑一次让 viewfinderAspect 有值即可。
            val aspect = viewfinderAspect
            val ocr = ml.recognizeBitmap(bmp, aspect)
            bmp.recycle()
            Log.i(
                TAG,
                "ocrimg: ${ocr.text.length}字/${ocr.elapsedMs}ms " +
                    "lines=${ocr.lines.size} aspect=$aspect",
            )
            ocr.lines.forEachIndexed { i, ln ->
                Log.i(TAG, "  line[$i] y=%.2f %s".format(ln.centerY, ln.text.take(60)))
            }
            val store = bank
            if (store != null && indexReady && ocr.ok) {
                val hits = searchAroundCenter(store, ocr)
                hits.take(3).forEachIndexed { i, h ->
                    Log.i(TAG, "  hit[$i] %.3f #%d %s".format(h.score, h.docId, h.stem.take(50)))
                }
                // 验收口径：把离屏识别的结果也推给界面。作用是复现"相机正在跑 → 突然出答案"
                // 这一跳，用来验证出答案时相机会话被干净解绑（否则 CameraX 会去重配 surface，
                // 界面上就表现为"识别成功后卡住"）。平时这条路径只在 --es ocrimg 时走到。
                // 与 handleFrame 走同一条硬校验：条件对不上就不给答案
                val best = hits.firstOrNull()
                val conflict = if (best != null && best.score >= MIN_SCORE) {
                    conditionConflict(lastMatchText, best.stem)
                } else {
                    null
                }
                if (best != null && conflict != null) {
                    Log.i(
                        TAG,
                        "条件不符（$conflict）q=${lastMatchText.replace('\n', ' ').take(80)} " +
                            "| doc=${best.stem.replace('\n', ' ').take(80)}",
                    )
                    candidates = emptyList()
                    currentCandidate = 0
                    locked = false
                    lastOcrChars = ocr.text.length
                    lastOcrMs = ocr.elapsedMs
                    lastOcrPreview = ocr.text.take(400)
                    emitLive(hint = "题干与题库不符（$conflict）· 不给答案")
                    maybeAskAi(ocr.text)
                    return@launch
                }
                candidates = hits
                currentCandidate = 0
                locked = hits.isNotEmpty()
                lastOcrChars = ocr.text.length
                lastOcrMs = ocr.elapsedMs
                lastOcrPreview = ocr.text.take(400)
                val found = best != null && best.score >= MIN_SCORE
                emitLive(hint = if (!found) "题库里没找到 · 识别 ${ocr.text.length} 字" else "")
                // 与相机那条路同一条规则：题库没答案才问 AI
                if (!found) maybeAskAi(ocr.text)
                val nowHit = (_state.value as? QuizUiState.Live)?.hit
                Log.i(TAG, "ocrimg emit: cands=${candidates.size} hitDoc=${nowHit?.docId} hitScore=${nowHit?.score}")
            }
        }
    }

    /**
     * 跳过相机，直接从一张图片文件里解二维码换题库。
     *
     * 同样是验收口子：`--es qrimg /data/local/tmp/qr.png`。
     * 和扫码走完全相同的解码器与后续流程，只差"帧从哪来"。
     */
    fun scanFromImage(path: String) {
        if (scanBusy) return
        scanBusy = true
        (scanAnalyzerRef as? QrCodeScanner)?.stop()
        viewModelScope.launch {
            val result = runCatching { QrCodeScanner.decodeFile(getApplication(), path) }
            scanBusy = false
            result.onSuccess { url ->
                if (url.isNullOrBlank()) {
                    Log.w(TAG, "图片里没认到二维码：$path")
                    _scan.value = ScanState.Failed("这张图里没有二维码")
                } else {
                    Log.i(TAG, "从图片解出：$url")
                    onScannedUrl(url)
                }
            }.onFailure { e ->
                Log.e(TAG, "解图片失败：$path", e)
                _scan.value = ScanState.Failed(describeScanError(e))
            }
        }
    }

    /** 离开扫码页：停掉识别、省电。进行中的下载/编译不打断。 */
    fun endScan() {
        (scanAnalyzerRef as? QrCodeScanner)?.stop()
        if (!scanBusy) _scan.value = ScanState.Idle
    }

    private fun newScanAnalyzer(): ImageAnalysis.Analyzer = QrCodeScanner(
        onUrl = { url -> onScannedUrl(url) },
        onFrame = { onScanFrame() },
    )

    /** 每识别完一帧调用一次，用来算「每秒认出几帧」——卡住时能一眼看出相机没在跑。 */
    private fun onScanFrame() {
        val now = SystemClock.elapsedRealtime()
        synchronized(scanFrameTimes) {
            scanFrameTimes.addLast(now)
            while (scanFrameTimes.size > SCAN_FPS_WINDOW) scanFrameTimes.removeFirst()
        }
        if (now - lastScanEmit < 500L) return
        lastScanEmit = now
        if (_scan.value is ScanState.Scanning) _scan.value = ScanState.Scanning(scanFps())
    }

    private fun scanFps(): Int {
        val times = synchronized(scanFrameTimes) { scanFrameTimes.toList() }
        if (times.size < 2) return 0
        val span = times.last() - times.first()
        return if (span <= 0L) 0 else ((times.size - 1) * 1000L / span).toInt()
    }

    /**
     * 扫到二维码：URL → 下载 → 编译 → 热替换。
     *
     * 全程在 viewModelScope 里跑，所以即便用户按返回离开这一页，任务也会继续走完
     * （题库换好总比半途而废强），只是界面上暂时看不到进度。
     */
    private fun onScannedUrl(url: String) {
        if (scanBusy) return
        scanBusy = true
        // 已经拿到地址，就不再花算力识别了
        (scanAnalyzerRef as? QrCodeScanner)?.stop()
        _scan.value = ScanState.Found(url)
        viewModelScope.launch {
            val t0 = SystemClock.elapsedRealtime()
            try {
                _scan.value = ScanState.Downloading(url, 0L, -1L)
                val bytes = withContext(Dispatchers.IO) {
                    BankDownloader.download(url) { received, total ->
                        _scan.value = ScanState.Downloading(url, received, total)
                    }
                }
                if (bytes.size < MIN_BANK_BYTES) {
                    throw IllegalStateException("文件只有 ${bytes.size} 字节，不像题库")
                }
                _scan.value = ScanState.Compiling(url, bytes.size)
                val name = fileNameOf(url, bytes)
                val loaded = withContext(Dispatchers.IO) { loader.acceptUpload(name, bytes) }
                val elapsed = SystemClock.elapsedRealtime() - t0
                applyLoaded(loaded, elapsed)
                _scan.value = ScanState.Done(
                    title = loaded.title,
                    questions = loaded.questionCount,
                    byteSize = loaded.byteSize,
                    elapsedMs = elapsed,
                )
                Log.i(TAG, "扫码换题库成功：$url → ${loaded.title} ${loaded.questionCount} 题 ${elapsed}ms")
            } catch (e: Throwable) {
                Log.e(TAG, "扫码换题库失败：$url", e)
                _scan.value = ScanState.Failed(describeScanError(e))
            } finally {
                scanBusy = false
            }
        }
    }

    /**
     * 从 URL 猜文件类型。
     *
     * 很多网盘/图床的直链没有扩展名（`.../file?id=123`），所以先看路径后缀，
     * 猜不出来就读头两个字节认 `OQSY` 魔数——索引和 json 的区分只看内容最可靠。
     */
    private fun fileNameOf(url: String, bytes: ByteArray): String {
        val path = runCatching { java.net.URI(url).path.orEmpty() }.getOrDefault("")
        val last = path.substringAfterLast('/').lowercase()
        val looksLikeIdx = bytes.size >= 4 &&
            bytes[0] == 'O'.code.toByte() &&
            bytes[1] == 'Q'.code.toByte() &&
            bytes[2] == 'S'.code.toByte() &&
            bytes[3] == 'Y'.code.toByte()
        return when {
            last.endsWith(".idx") -> "qbank.idx"
            last.endsWith(".json") -> "qbank.json"
            looksLikeIdx -> "qbank.idx"
            else -> "qbank.json"
        }
    }

    /** 把底层的英文异常翻译成一眼能看懂的一句话。 */
    private fun describeScanError(e: Throwable): String {
        val raw = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        return when {
            raw.contains("Unable to resolve host", true) || raw.contains("UnknownHost", true) ->
                "域名解析失败，检查眼镜是否连上网"
            raw.contains("timed out", true) || raw.contains("timeout", true) ->
                "连接超时，地址可能不通"
            raw.contains("HTTP 404", true) -> "404 文件不存在"
            raw.contains("HTTP 403", true) -> "403 无权限访问"
            raw.contains("HTTP 5", true) -> "服务端错误（$raw）"
            raw.contains("SSL", true) || raw.contains("certificate", true) -> "证书校验失败"
            raw.contains("ENOSPC", true) || raw.contains("space", true) -> "存储空间不足"
            else -> raw
        }
    }

    override fun onCleared() {
        runCatching { uploadServer?.shutdown() }
        uploadServer = null
        super.onCleared()
    }

    private companion object {
        const val TAG = "SolveViewModel"
        const val MAX_CANDIDATES = 3

        /**
         * 每次检索取多少个候选。
         *
         * 只要够翻页即可：界面最多展示 [MAX_CANDIDATES] 个，窗口打分只用到第 1 名的分数。
         * 这个值直接决定检索耗时——`QBankStore.search` 的 topK 是"逐个挑最大"的线性选择，
         * 总比较次数 = topK × 题数。20 时一次 6600 题要扫 13 万次，一拍照片还要跑 5~6 个窗口；
         * 降到 6 后同样一拍减少约七成比较。
         */
        const val SEARCH_TOPK = 6

        /** 中心窗口最多取几行去检索：再长就跨到隔壁题去了。 */
        const val MAX_WINDOW_LINES = 6

        /** 窗口文本少于这么多字就不参与比较，避免短文本拿到虚高分。 */
        const val MIN_WINDOW_CHARS = 12

        /** 取景框的宽高比（480 / 220）；界面还没量出真实尺寸时用它兜底。 */
        const val DEFAULT_BOX_ASPECT = 480f / 220f

        /** 低于此分值视为「没扫到」，不刷新答案（防乱跳）。 */
        const val MIN_SCORE = 0.55f

        /** 选项行（`A. 12 万元`）里的数字不属于题干，做条件校验前先剔掉。 */
        val OPTION_LINE = Regex("^[A-FＡ-Ｆa-f][.、．)）].*")

        /** 行首题号：`12.` / `12、`。 */
        val RE_LEADING_NO = Regex("^\\d+[.、]\\s*")

        /** 子问标记：`（1）`。 */
        val RE_SUBQ_NO = Regex("[（(]\\s*\\d{1,2}\\s*[）)]")

        val NUMBER = Regex("\\d+(?:\\.\\d+)?")

        /** 否定标记——多一个少一个都可能把答案整个翻转，所以都算硬条件。 */
        val NEGATION = Regex("不正确|不属于|不包括|不能|不得|不宜|除外|除了|错误|不|无|非")

        /** 认到的文字至少要覆盖原题题干这个比例，才敢就「少了否定词」下结论。 */
        const val FULL_STEM_RATIO = 0.75f

        /**
         * 拍照节奏：每 3 秒拍一张。
         *
         * 早先做过「画面变了就 350ms 拍一次」的连续识别，实际用起来是屏幕一直在跳、
         * 用户看不清也定不住。改成固定节拍后，一张照片对应一次结果，中间是静止的。
         */
        const val SHOT_INTERVAL_MS = 3_000L

        /** 防抖：最近 N 次命中里同一题出现 M 次才锁定。 */
        const val VOTE_WINDOW = 5
        const val LOCK_VOTES = 2

        const val ENGINE_NAME = "ML Kit 中文(bundled)"

        /** 扫码页算帧率用的滑动窗口。 */
        const val SCAN_FPS_WINDOW = 40

        /** 下载到的文件小于这个体积就不当题库（防止把错误页/图标当天题库）。 */
        const val MIN_BANK_BYTES = 8 * 1024L

        /** 两次 AI 调用之间至少隔这么久——一拍 3 秒，不节流就变成每 3 秒一次请求。 */
        const val AI_MIN_INTERVAL_MS = 15_000L

        /** 认到的正文短于这个字数就不值得问模型（多半是只拍到了半行）。 */
        const val MIN_AI_QUESTION_CHARS = 8
    }
}

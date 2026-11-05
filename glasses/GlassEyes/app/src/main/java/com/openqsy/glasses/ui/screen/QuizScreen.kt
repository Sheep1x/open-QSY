package com.openqsy.glasses.ui.screen

import android.app.Activity
import android.util.Range
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openqsy.glasses.camera.rememberCameraBound
import com.openqsy.glasses.input.BareKeyEvent
import com.openqsy.glasses.input.RegisterBareKeyHandler
import com.openqsy.glasses.quiz.QuizHit
import com.openqsy.glasses.solve.DebugInfo
import com.openqsy.glasses.solve.QuizUiState
import com.openqsy.glasses.solve.ScanState
import com.openqsy.glasses.solve.SolveViewModel
import com.openqsy.glasses.ui.design.BareHeroText
import com.openqsy.glasses.ui.design.BareKeyGuide
import com.openqsy.glasses.ui.design.BareScreenLayout
import com.openqsy.glasses.ui.design.BareTokens
import com.openqsy.glasses.ui.theme.NeonGreen
import java.util.concurrent.Executors
import kotlinx.coroutines.delay

/**
 * 眼镜端主界面：**定时拍照识题**，每 3 秒自动拍一张，用户不需要点任何键。
 *
 * 交互：
 * - 单击：暂停 / 继续拍照（想停下来读答案、或省电时）
 * - 双击：**返回** —— 调试页→关闭；有答案→清空回扫描；暂停中→继续；无内容→退出应用
 * - 单指前滑 / 后滑：在 top-3 候选里切换
 * - 长按：调试页（索引规模 / OCR 耗时 / 匹配耗时 / 拍照频次 / 丢弃帧数）
 *
 * 相机同时绑 [ImageAnalysis] 与 [Preview]：前者给 OCR，后者就是面板顶部那个取景框——
 * 没有取景框时用户根本不知道相机在看哪儿，这是"看不清识别的是哪部分"的根因。
 */
@Composable
fun QuizScreen(
    viewModel: SolveViewModel,
    cameraGranted: Boolean,
    onRequestCamera: () -> Unit,
    onExit: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state) {
        Log.d("BareQuiz", "state → ${state::class.simpleName} hit=${(state as? QuizUiState.Live)?.hit?.docId}")
    }
    val debug by viewModel.debug.collectAsStateWithLifecycle()
    val scan by viewModel.scan.collectAsStateWithLifecycle()
    val shotSeq by viewModel.shot.collectAsStateWithLifecycle()

    // 每真正拍下一张，取景框边闪一下。没有这层反馈时画面看起来是静止的，
    // 用户会以为自己根本没在拍（"不是不停的识别，而是拍一张、过几秒再拍"）。
    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(shotSeq) {
        if (shotSeq > 0) {
            flash = true
            delay(220)
            flash = false
        }
    }
    // 调试用入口：`adb shell am start ... --es open scan` 可直接落到指定页面。
    // 眼镜没有 adb 能模拟的触摸/长按，验收界面时只能靠这个口子把页面打开再截图。
    val activity = LocalContext.current as? Activity
    // 验收口子：`--es crop 0` 关掉「按取景框裁剪」，退回整帧识别，用于对比诊断。
    val cropEnabled = remember { activity?.intent?.getStringExtra("crop") != "0" }
    // 验收口子：`--es ocrimg <路径>` 对一张图跑完整的裁剪+识别+检索，只打日志。
    val debugOcrImg = remember { activity?.intent?.getStringExtra("ocrimg") }
    LaunchedEffect(debugOcrImg) {
        if (!debugOcrImg.isNullOrBlank()) viewModel.ocrFromImage(debugOcrImg)
    }
    var page by remember {
        mutableStateOf(
            when {
                // 给了 url 就直接进扫码页看下载进度
                !activity?.intent?.getStringExtra("url").isNullOrBlank() -> Page.Scan
                // 给了一张二维码图片，同样进扫码页，但走"读图"而不是"读相机"
                !activity?.intent?.getStringExtra("qrimg").isNullOrBlank() -> Page.Scan
                activity?.intent?.getStringExtra("open") in setOf("scan", "transfer") -> Page.Scan
                activity?.intent?.getStringExtra("open") == "debug" -> Page.Debug
                else -> Page.Main
            }
        )
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 单线程：OCR 与匹配都在这一条线上串行执行，避免并发抢 CPU（Go 设备只有小核）
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    val analysis = remember {
        ImageAnalysis.Builder()
            // 关键：只保留最新帧，旧帧直接丢，避免"识别跟不上导致画面越积越慢"
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1024, 768),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        )
                    )
                    .build()
            )
            .build()
    }

    // 取景框：单绿屏上没法靠"感觉"对准，必须让用户看见相机拍到的画面。
    // 用 COMPATIBLE（TextureView 而非 SurfaceView），这样它走普通 View 层级，
    // 能被 Compose 裁切、也能被 screencap 截到，调试时才看得见。
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val preview = remember {
        Preview.Builder()
            .setTargetFrameRate(Range(10, 15))
            .build()
            .apply { setSurfaceProvider(previewView.surfaceProvider) }
    }

    // 分析器随页面切换：实时识别页挂 OCR，扫码换题库页挂条码识别。
    // 两者不能同时挂——同一个 ImageAnalysis 只能有一个 analyzer。
    LaunchedEffect(analysis, cameraGranted, page) {
        if (!cameraGranted) return@LaunchedEffect
        if (page == Page.Scan) {
            viewModel.beginScan()
            analysis.setAnalyzer(analysisExecutor, viewModel.scanAnalyzer)
        } else {
            viewModel.endScan()
            analysis.setAnalyzer(analysisExecutor, viewModel.analyzer)
        }
    }
    DisposableEffect(analysis) {
        onDispose {
            runCatching { analysis.clearAnalyzer() }
            runCatching { analysisExecutor.shutdown() }
        }
    }

    // 验收口子：`--es url <地址>` 直接跑「下载→编译→热替换」全流程，跳过扫码那一步
    val debugUrl = remember { activity?.intent?.getStringExtra("url").orEmpty() }
    LaunchedEffect(debugUrl) {
        if (debugUrl.isNotBlank()) viewModel.scanFromUrl(debugUrl)
    }

    // 验收口子：`--es qrimg <图片路径>` 用同一个解码器读一张现成的二维码图，
    // 把真机上"举着眼镜对准屏幕"换成"读文件"，其余环节完全一致
    val debugQrImg = remember { activity?.intent?.getStringExtra("qrimg").orEmpty() }
    LaunchedEffect(debugQrImg) {
        if (debugQrImg.isNotBlank()) viewModel.scanFromImage(debugQrImg)
    }

    // 验收排版用：`am start ... --es demo short|long|calc|ocr` 直接灌一条假命中。
    // 眼镜上没法远程制造真实的识别结果，改答案区排版时只能这样把不同长度的答案渲染出来截图核对。
    // 定义在相机绑定之前：相机会话要跟着「有没有答案」走，验收口子得走同一条路。
    val demoState = remember {
        // `--es demo ocr`：造「认出了字但题库里没有」的状态，用来核对取景框占位后
        // 剩下那 103px 能不能装下「对准题目 + 一行提示 + 2 行 OCR 原文」。
        if (activity?.intent?.getStringExtra("demo") == "ocr") {
            return@remember QuizUiState.Live(
                hit = null,
                locked = false,
                candidateIndex = 0,
                candidateCount = 0,
                ocrMs = 420,
                matchMs = 6,
                scansPerMin = 12,
                paused = false,
                hint = "题库里没找到 · 识别 96 字",
                ocrPreview = DEMO_OCR_PREVIEW,
            )
        }
        val answer = when (activity?.intent?.getStringExtra("demo")) {
            "short" -> "C. 中国共产党第十三次全国代表大会"
            "long" -> DEMO_LONG_ANSWER
            "calc" -> DEMO_CALC_ANSWER
            else -> null
        }
        answer?.let {
            QuizUiState.Live(
                hit = QuizHit(docId = 0, score = 0.87f, stem = DEMO_STEM, answer = it,
                    subject = "房地产制度法规政策"),
                locked = true,
                candidateIndex = 0,
                candidateCount = 1,
                ocrMs = 6,
                matchMs = 1,
                scansPerMin = 12,
                paused = false,
                hint = "",
            )
        }
    }
    val shownDemo = demoState ?: state

    // 暂停 = 真的停：连相机都不绑定。否则"暂停"只是不送 OCR，预览照样持续推流，
    // 镜腿该热还是热。代价是恢复时要重新绑定，画面会黑一两秒。
    val pausedNow = (shownDemo as? QuizUiState.Live)?.paused == true
    // 相机会话只管"授权 + 有没有暂停"，不跟着"有没有答案"走：
    // 用户要的是"拍一张 → 出答案 → 过几秒再拍再识别"，一旦出答案就解绑，
    // 后面那几张就拍不成了。出答案时的卡顿改由取景框那边解决（压到 1dp，不 detach）。
    val hasHit = (shownDemo as? QuizUiState.Live)?.hit != null
    LaunchedEffect(cameraGranted, pausedNow, hasHit) {
        Log.d(
            "BareQuiz",
            "bind state: granted=$cameraGranted paused=$pausedNow hasHit=$hasHit " +
                "→ enabled=${cameraGranted && !pausedNow}",
        )
    }
    rememberCameraBound(
        context = context,
        lifecycleOwner = lifecycleOwner,
        enabled = cameraGranted && !pausedNow,
        onReady = { viewModel.onCameraReady() },
        onError = { viewModel.onCameraError(it) },
        useCases = { arrayOf(preview, analysis) },
    )

    RegisterBareKeyHandler { event ->
        when (page) {
            Page.Main -> when (event) {
                BareKeyEvent.Click -> {
                    if (!cameraGranted) onRequestCamera() else viewModel.togglePause()
                }

                // 双击 = 系统返回（KEYCODE_BACK）：清结果 → 解暂停 → 退出应用
                BareKeyEvent.DoubleClick -> {
                    val live = state as? QuizUiState.Live
                    when {
                        live?.hit != null -> viewModel.reset()
                        live?.paused == true -> viewModel.togglePause()
                        else -> onExit()
                    }
                }

                BareKeyEvent.LongPress -> page = Page.Debug
                BareKeyEvent.SwipeForward -> viewModel.cycleCandidate(forward = true)
                BareKeyEvent.SwipeBack -> viewModel.cycleCandidate(forward = false)
            }

            // 调试页里"单击"当确认用：进换题库页扫码
            Page.Debug -> when (event) {
                BareKeyEvent.Click -> page = Page.Scan
                BareKeyEvent.DoubleClick, BareKeyEvent.LongPress -> page = Page.Main
                BareKeyEvent.SwipeForward, BareKeyEvent.SwipeBack -> Unit
            }

            // 换题库页：扫完或失败时单击 = 再扫一次，其余状态单击不响应（避免误触中断下载）
            Page.Scan -> when (event) {
                BareKeyEvent.Click -> when (scan) {
                    is ScanState.Done, is ScanState.Failed -> viewModel.beginScan()
                    else -> Unit
                }

                BareKeyEvent.DoubleClick, BareKeyEvent.LongPress -> page = Page.Main
                BareKeyEvent.SwipeForward, BareKeyEvent.SwipeBack -> Unit
            }
        }
        true
    }

    // 换题库页要把整块屏幕让给"对准二维码"，所以不套用带标题栏和按键图例的常规布局
    if (page == Page.Scan) {
        ScanBankPage(
            state = scan,
            bankBrief = debug.bankBrief,
            previewView = if (cameraGranted) previewView else null,
        )
        return
    }

    val shown = shownDemo
    val live = shown as? QuizUiState.Live
    val paused = live?.paused == true

    val guide = if (page == Page.Debug) {
        BareKeyGuide(click = "换题库", doubleClick = "返回", longPress = "返回")
    } else {
        // 4 行 × 18 + 4 = 76px，在 LegendH(84) 之内；再加行就会压到上面板子
        BareKeyGuide(
            click = if (paused) "继续扫描" else "暂停",
            swipeForward = "换候选（前/后滑）",
            doubleClick = "返回",
            longPress = "调试",
        )
    }

    BareScreenLayout(
        title = "题眼 · 拍照",
        subtitle = subtitleOf(shown, cameraGranted, paused, shotSeq),
        keyGuide = guide,
        // 安全区参考框是给开发对齐用的，正式界面画出来会横穿文字，看起来像重叠
        drawSafeAreaFrame = false,
    ) {
        if (page == Page.Debug) {
            DebugBody(debug)
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 出了答案不收起取景框，只从 140dp 压到 100dp：用户要看得出相机还对着
                // 哪一段（谁都不想让答案对到别的题上），同时把 40px 还给答案区。
                // 关键是它始终留在组合里——一旦移出，PreviewView detach，CameraX 去重配
                // surface 会撞 5000ms 超时，界面就卡住（踩过）。
                if (cameraGranted && !paused) {
                    val idle = live?.hit == null
                    CameraViewfinder(
                        view = previewView,
                        height = if (idle) BareTokens.ViewfinderH else BareTokens.ViewfinderCompactH,
                        flash = flash,
                        onSize = { w, h ->
                            // 只认「还没出答案」时的高度来算裁剪比例：出答案后高度变成
                            // 100dp，按它算会把识别区裁得更窄，反而认不出，来回抖。
                            if (cropEnabled && idle && w > 0 && h > 20) {
                                viewModel.setViewfinderAspect(w.toFloat() / h)
                            }
                        },
                    )
                    Spacer(Modifier.height(BareTokens.LineGap))
                }
                MainBody(
                    state = shown,
                    paused = paused,
                    onRequestCamera = onRequestCamera,
                    withViewfinder = cameraGranted && !paused,
                )
            }
        }
    }
}

// ---- 仅供 `--es demo` 验收排版使用 ----
private const val DEMO_OCR_PREVIEW =
    "（ ）在中国社会主义经济发展史上第一次提出了建立房地产市场，确立了房地产市场的地位 " +
        "A．党的十一届三中全会 B．党的十二大 C．党的第十三次全国代表大会 " +
        "D．党的十五大 参考答案：C 解析：1987 年党的十三大首次提出"

private const val DEMO_STEM = "（ ）在中国社会主义经济发展史上第一次提出了建立房地产市场，确立了房地产市场的地位。"

private const val DEMO_LONG_ANSWER =
    "A. 土地使用权出让合同约定的使用年限届满，土地使用者需要继续使用土地的，应当最迟于届满前一年申请续期\n" +
        "B. 除根据社会公共利益需要收回该幅土地的，出让方应当予以批准"

private const val DEMO_CALC_ANSWER =
    "【第一小题解答】1.出售部分 1）销售收入：13000×6500＝8450（万元）2）销售税费：8450×5.5%＝464.75（万元）" +
        "2.出租部分 1）潜在毛租金收入：100×330×365＝1204.5（万元）2）有效毛租金收入：经营期第1～3年：" +
        "1204.5×70%＝843.15（万元）经营期第4～20年：1204.5×85%＝1023.83（万元）3）运营费用：经营期第1～3年：" +
        "843.15×30%＝252.95（万元）经营期第4～20年：1023.83×35%＝358.34（万元）4）净收益：" +
        "843.15－252.95＝590.20（万元）1023.83－358.34＝665.49（万元）"

/** 三个页面：定时拍照识别 / 调试信息 / 换题库（扫二维码）。键位含义随页面变化，见底部图例。 */
private enum class Page { Main, Debug, Scan }

private fun subtitleOf(
    state: QuizUiState,
    granted: Boolean,
    paused: Boolean,
    shots: Int,
): String = when {
    !granted -> "需要相机权限 · 单击申请"
    paused -> if (shots > 0) "已暂停 · 共拍 $shots 张" else "已暂停 · 单击继续"
    state is QuizUiState.LoadingIndex -> "题库装载中…"
    state is QuizUiState.Live -> {
        val lock = if (state.locked) "已确认" else "拍照中"
        // 张数是唯一能证明"还在按节拍拍"的证据：画面本身几乎不动。
        if (shots > 0) "$lock · 已拍 $shots 张" else "$lock · 每 3 秒一张"
    }

    else -> ""
}

/**
 * 取景框：相机实时画面。
 *
 * 之前完全不显示预览，用户根本不知道相机对着哪儿、认的是屏幕上哪一块题目，
 * 只能靠猜位置。这里把 Preview 的画面直接摆在面板最上方，
 * 看到的范围就是每次拍照送去 OCR 的范围（同一路相机、同一 FOV）。
 */
@Composable
internal fun CameraViewfinder(
    view: PreviewView,
    height: Dp = BareTokens.ViewfinderH,
    fit: Boolean = false,
    flash: Boolean = false,
    onSize: ((Int, Int) -> Unit)? = null,
) {
    // 换题库页要看到整个二维码，主界面只要看到"中间那几行字"，两者的取景方式不同
    LaunchedEffect(fit) {
        view.scaleType = if (fit) PreviewView.ScaleType.FIT_CENTER
        else PreviewView.ScaleType.FILL_CENTER
    }
    // 必须自己裁：PreviewView 里的 TextureView 是独立的绘图层，不受父级 Compose 裁切约束，
    // 不夹住的话相机会一直画到整个面板上，把下面的文字全糊掉（实测就是这样）。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .onSizeChanged { onSize?.invoke(it.width, it.height) }
            .clipToBounds()
            .border(
                width = if (flash) 3.dp else 1.dp,
                color = NeonGreen.copy(alpha = if (flash) 1f else 0.5f),
            ),
    ) {
        AndroidView(
            factory = { view },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun MainBody(
    state: QuizUiState,
    paused: Boolean,
    onRequestCamera: () -> Unit,
    withViewfinder: Boolean = false,
) {
    when (state) {
        is QuizUiState.LoadingIndex -> BareHeroText(
            text = "题库装载中…",
            hint = "首次启动约 1~2 秒",
        )

        is QuizUiState.Failure -> Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "不可用",
                color = NeonGreen,
                fontSize = 18.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Divider()
            Text(
                text = state.message,
                color = NeonGreen,
                fontSize = BareTokens.BodySp,
                lineHeight = BareTokens.BodyLineH,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "长按看调试",
                color = NeonGreen.copy(alpha = 0.75f),
                fontSize = BareTokens.CaptionSp,
                lineHeight = BareTokens.CaptionLineH,
                maxLines = 1,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        is QuizUiState.Live -> {
            val hit = state.hit
            if (hit == null) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (paused) "已暂停" else "对准题目",
                        color = NeonGreen,
                        fontSize = 18.sp,
                        lineHeight = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(
                        text = if (paused) "单击继续扫描" else state.hint,
                        color = NeonGreen.copy(alpha = 0.75f),
                        fontSize = BareTokens.BodySp,
                        lineHeight = BareTokens.BodyLineH,
                        maxLines = if (withViewfinder) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    // 把 OCR 原文显示出来：出不来答案时，用户一眼能判断是「没识别对」还是「题库没有」
                    if (state.ocrPreview.isNotBlank()) {
                        Divider()
                        Text(
                            text = "识别到：",
                            color = NeonGreen.copy(alpha = 0.6f),
                            fontSize = BareTokens.CaptionSp,
                            lineHeight = BareTokens.CaptionLineH,
                            maxLines = 1,
                        )
                        Text(
                            text = state.ocrPreview,
                            color = NeonGreen,
                            fontSize = BareTokens.CaptionSp,
                            lineHeight = BareTokens.CaptionLineH,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        // 主界面别摆 IP 地址或网址——那是给技术人员看的。这里只放一句入口提示，
                        // 真正的操作在换题库页：举着眼镜扫电脑上的二维码。
                        Text(
                            text = "换题库：长按 → 单击",
                            color = NeonGreen.copy(alpha = 0.55f),
                            fontSize = BareTokens.CaptionSp,
                            lineHeight = BareTokens.CaptionLineH,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // 选项原文、简答与计算题的答案动辄上百字，480×640 一屏放不下：
                    // 短答案维持 22sp 醒目；长答案降到 16sp，并把题干压到 1 行，
                    // 把省下来的高度全给答案。上限 7 行是按"面板 329 − 取景框 84 −
                    // 间隙/分隔线/说明行"倒推的，layout_preview.py 的 stress 用例卡的就是这条线。
                    val answerText = hit.answer.ifBlank { "（题库无答案）" }
                    val longAnswer = answerText.length > 30 || answerText.count { it == '\n' } > 1
                    Text(
                        text = when {
                            paused -> "答案（已暂停）"
                            state.locked -> "答案 ✓"
                            else -> "答案（识别中）"
                        },
                        color = NeonGreen.copy(alpha = 0.8f),
                        fontSize = BareTokens.CaptionSp,
                        lineHeight = BareTokens.CaptionLineH,
                        maxLines = 1,
                    )
                    Text(
                        text = answerText,
                        color = NeonGreen,
                        fontSize = if (longAnswer) BareTokens.AnswerSmallSp else BareTokens.AnswerSp,
                        lineHeight = if (longAnswer) BareTokens.AnswerSmallLineH else BareTokens.AnswerLineH,
                        fontWeight = FontWeight.Bold,
                        maxLines = if (longAnswer) 7 else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Divider()
                    Text(
                        text = hit.stem,
                        color = NeonGreen,
                        fontSize = BareTokens.BodySp,
                        lineHeight = BareTokens.BodyLineH,
                        maxLines = if (longAnswer) 1 else 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Divider()
                    Text(
                        text = buildString {
                            append("匹配 ")
                            append("%.2f".format(hit.score))
                            append(" · ")
                            append(hit.subject)
                            if (state.candidateCount > 1) {
                                append(" · 候选 ")
                                append(state.candidateIndex + 1)
                                append('/')
                                append(state.candidateCount)
                            }
                            append(" · ")
                            append(state.ocrMs)
                            append('/')
                            append(state.matchMs)
                            append("ms")
                        },
                        color = NeonGreen.copy(alpha = 0.7f),
                        fontSize = BareTokens.CaptionSp,
                        lineHeight = BareTokens.CaptionLineH,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun DebugBody(debug: DebugInfo) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "调试",
            color = NeonGreen,
            fontSize = BareTokens.BodyLargeSp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Divider()
        DebugLine("引擎", debug.engine)
        DebugLine("索引", debug.indexStats)
        DebugLine("自检", debug.indexSelfCheck)
        DebugLine("OCR", "${debug.ocrChars} 字 · ${debug.ocrMs}ms")
        DebugLine("匹配", "${debug.matchMs}ms")
        DebugLine("频次", "${debug.scansPerMin} 次/分 · 跳过 ${debug.throttledSkips}")
        DebugLine("候选分", debug.topScores)
        DebugLine("题库", debug.bankSource)
        if (debug.uploadUrl.isNotBlank()) DebugLine("换题库", debug.uploadUrl)
        debug.error?.let { DebugLine("错误", it) }
        if (debug.ocrPreview.isNotBlank()) {
            Text(
                text = debug.ocrPreview,
                color = NeonGreen.copy(alpha = 0.7f),
                fontSize = BareTokens.CaptionSp,
                lineHeight = BareTokens.CaptionLineH,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun DebugLine(label: String, value: String) {
    Text(
        text = "$label  ${value.ifBlank { "-" }}",
        color = NeonGreen,
        fontSize = BareTokens.CaptionSp,
        lineHeight = BareTokens.CaptionLineH,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Divider() {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(1.dp),
    ) {
        drawLine(
            color = NeonGreen.copy(alpha = 0.5f),
            start = Offset(0f, 0f),
            end = Offset(size.width, 0f),
            strokeWidth = BareTokens.STROKE_THIN,
        )
    }
}

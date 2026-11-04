package com.openqsy.glasses.ui.screen

import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openqsy.glasses.solve.ScanState
import com.openqsy.glasses.ui.design.BareTokens

/**
 * 换题库页 —— **眼镜扫二维码**，从二维码里的地址下载题库并现场编译。
 *
 * 为什么是这个方向：
 * - 眼镜的画面是投影，手机拍不了，所以二维码不能由眼镜显示；
 * - 反过来眼镜有相机，扫电脑屏幕上的二维码很自然；
 * - 二维码里只是一个 URL，电脑上用任何二维码工具生成即可，
 *   题库放局域网（`http://192.168.x.x:8000/题库.json`）或任意公网地址都行。
 *
 * 拿到文件后不是"存下来就完事"：json 要在设备端编译成索引（[com.openqsy.glasses.quiz.IndexBuilder]），
 * 编译完再热替换当前题库，全流程不重启应用。
 */
@Composable
fun ScanBankPage(
    state: ScanState,
    bankBrief: String,
    previewView: PreviewView? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = BareTokens.ScreenPadH),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 对准二维码时必须有取景画面：不然用户举着眼镜对着电脑屏幕全靠猜，
        // 只看到一个空框和"对准二维码"几个字（这是最初被吐槽的问题）。
        // fit = true → FIT_CENTER，整帧可见，不会因为裁切而"看着在框里却扫不到"。
        val aiming = state is ScanState.Idle || state is ScanState.Scanning
        if (previewView != null && aiming) {
            CameraViewfinder(previewView, height = 260.dp, fit = true)
            Spacer(Modifier.height(12.dp))
        }

        Text(
            text = headlineOf(state),
            color = Color.White,
            fontSize = 22.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(10.dp))

        Text(
            text = detailOf(state),
            color = Color.White.copy(alpha = 0.85f),
            fontSize = BareTokens.BodySp,
            lineHeight = BareTokens.BodyLineH,
            textAlign = TextAlign.Center,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )

        // 下载进度条：没有数字也能看出"在动"，比纯文字让人安心
        val downloading = state as? ScanState.Downloading
        if (downloading != null) {
            Spacer(Modifier.height(12.dp))
            val fraction = if (downloading.total > 0) {
                (downloading.received.toFloat() / downloading.total).coerceIn(0f, 1f)
            } else {
                0f
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(Color.White.copy(alpha = 0.22f)),
            ) {
                if (fraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(6.dp)
                            .background(Color.White),
                    )
                }
            }
        }

        if (bankBrief.isNotBlank() && state !is ScanState.Done) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "当前：" + bankBrief,
                color = Color.White.copy(alpha = 0.55f),
                fontSize = BareTokens.CaptionSp,
                lineHeight = BareTokens.CaptionLineH,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(14.dp))

        // 这一页没有底部按键图例，键位说明直接写在页面上
        Text(
            text = keyHintOf(state),
            color = Color.White.copy(alpha = 0.5f),
            fontSize = BareTokens.CaptionSp,
            lineHeight = BareTokens.CaptionLineH,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun headlineOf(state: ScanState): String = when (state) {
    is ScanState.Idle -> "换题库"
    is ScanState.Scanning -> "对准二维码"
    is ScanState.Found -> "已识别地址"
    is ScanState.Downloading -> {
        val pct = if (state.total > 0) {
            (state.received * 100 / state.total).toInt().coerceIn(0, 100)
        } else {
            null
        }
        if (pct != null) "下载中 $pct%" else "下载中"
    }

    is ScanState.Compiling -> "编译中…"
    is ScanState.Done -> "已更换"
    is ScanState.Failed -> "失败"
}

private fun detailOf(state: ScanState): String = when (state) {
    is ScanState.Idle -> ""
    is ScanState.Scanning ->
        "电脑把题库放上网（局域网或公网都行）\n把下载链接做成二维码，放进取景范围"

    is ScanState.Found -> state.url
    is ScanState.Downloading -> {
        val got = humanBytes(state.received)
        if (state.total > 0) "$got / ${humanBytes(state.total)}" else got
    }

    is ScanState.Compiling ->
        "${humanBytes(state.bytes.toLong())} · 编译约 1~4 秒"

    is ScanState.Done -> buildString {
        append(state.title.ifBlank { "未命名题库" })
        append('\n')
        append("${state.questions} 题 · ${humanBytes(state.byteSize.toLong())}")
        append(" · ${"%.1f".format(state.elapsedMs / 1000.0)} 秒")
    }

    is ScanState.Failed -> state.message
}

private fun keyHintOf(state: ScanState): String = when (state) {
    // 帧率不只是好看：为 0 就说明相机没在出帧，用户对着镜头干等会以为是二维码的问题
    is ScanState.Scanning -> "双击返回 · 识别中 ${state.fps} 帧/秒"
    is ScanState.Done -> "双击返回，单击继续扫描"
    is ScanState.Failed -> "单击重试 · 双击返回"
    else -> "双击返回"
}

private fun humanBytes(n: Long): String = when {
    n >= 1024L * 1024L -> "%.1f MB".format(n / 1024.0 / 1024.0)
    n >= 1024L -> "%.0f KB".format(n / 1024.0)
    else -> "$n B"
}

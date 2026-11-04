package com.openqsy.glasses.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * 本应用只需要相机。
 *
 * 刻意不提供录音/麦克风，也不提供任何存储写入的权限检查——
 * 相机帧只在内存里走一遍（ImageProxy → ML Kit → 文本），不落盘、不上传，
 * 见 `solve/SolveViewModel.kt` 的 `handleFrame` 与 `ocr/MlKitOcrEngine.kt`。
 */
object BarePermissions {
    fun hasCamera(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
}

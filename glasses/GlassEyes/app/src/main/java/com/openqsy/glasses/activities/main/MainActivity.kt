package com.openqsy.glasses.activities.main

import android.Manifest
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.openqsy.glasses.input.BareGlassesInputDispatcher
import com.openqsy.glasses.input.LocalBareGlassesInputDispatcher
import com.openqsy.glasses.input.SwipeDetector
import com.openqsy.glasses.input.rememberBareGlassesInputDispatcher
import com.openqsy.glasses.solve.SolveViewModel
import com.openqsy.glasses.ui.design.GlassesDisplayFrame
import com.openqsy.glasses.ui.screen.QuizScreen
import com.openqsy.glasses.ui.theme.GlassEyesTheme
import com.openqsy.glasses.ui.theme.PitchBlack
import com.openqsy.glasses.utils.BarePermissions

/**
 * 单 Activity 应用。
 *
 * 按键通路（见开发者文档「按键与佩戴和折叠」专章）：
 * - TouchPad 单指单击 → `KEYCODE_ENTER`（onKeyUp）→ BareKeyEvent.Click
 * - TouchPad 单指双击 → `KEYCODE_BACK`（onKeyDown）→ BareKeyEvent.DoubleClick
 * - TouchPad 单指长按 → `KEYCODE_PROG_BLUE` + 广播 `ACTION_AI_START` → BareKeyEvent.LongPress
 * - TouchPad 单指前滑/后滑 → `DPAD_RIGHT→DPAD_DOWN` / `DPAD_LEFT→DPAD_UP` 键序列 → SwipeForward/Back
 * - 镜腿单击 → 广播 `ACTION_SPRITE_BUTTON_CLICK` → BareKeyEvent.Click
 *
 * 注意：`KEYCODE_ENTER` 必须在 onKeyDown 里先吃掉，否则会触发焦点导航。
 */
class MainActivity : ComponentActivity() {

    private val solveViewModel by viewModels<SolveViewModel>()
    private var keyDispatcher: BareGlassesInputDispatcher? = null

    private var cameraGranted by mutableStateOf(false)

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            cameraGranted = granted
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupFullscreen()

        cameraGranted = BarePermissions.hasCamera(this)
        if (!cameraGranted) cameraPermissionLauncher.launch(Manifest.permission.CAMERA)

        // 题库上传服务：连上 WiFi 才起，手机浏览器输眼镜地址即可换题库。
        // 失败不影响主流程（没 WiFi 时本来也没法用）。
        solveViewModel.startUploadServer()

        setContent {
            val context = LocalContext.current
            val dispatcher = rememberBareGlassesInputDispatcher(context)
            remember { keyDispatcher = dispatcher }
            GlassEyesTheme {
                CompositionLocalProvider(LocalBareGlassesInputDispatcher provides dispatcher) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(PitchBlack),
                    ) {
                        GlassesDisplayFrame {
                            QuizScreen(
                                viewModel = solveViewModel,
                                cameraGranted = cameraGranted,
                                onRequestCamera = {
                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                },
                                onExit = { finish() },
                            )
                        }
                    }
                }
            }
        }
    }

    /** 单指前滑 / 后滑：不是广播，是 DPAD 键序列，需在 dispatchKeyEvent 里先拦 */
    private val swipeDetector = SwipeDetector(
        onSwipeForward = { keyDispatcher?.dispatchSwipeForward() },
        onSwipeBack = { keyDispatcher?.dispatchSwipeBack() },
    )

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (swipeDetector.onKeyDown(event.keyCode, event.eventTime)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> return true // 在 onKeyUp 派发，避免重复触发
            KeyEvent.KEYCODE_BACK -> {
                keyDispatcher?.dispatchBackKey()
                return true
            }

            KeyEvent.KEYCODE_PROG_BLUE -> {
                keyDispatcher?.dispatchLongKey()
                return true
            }

            KeyEvent.KEYCODE_SETTINGS -> {
                keyDispatcher?.consumeSystemKey("Key·SETTINGS")
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER && event != null && event.repeatCount == 0) {
            keyDispatcher?.dispatchEnterKey()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        // 用户可能是先开应用再连 WiFi，所以每次回到前台都再试一次（已启动时是空操作）
        solveViewModel.startUploadServer()
    }

    override fun onDestroy() {
        keyDispatcher?.unregister(applicationContext)
        keyDispatcher = null
        super.onDestroy()
    }

    private fun setupFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

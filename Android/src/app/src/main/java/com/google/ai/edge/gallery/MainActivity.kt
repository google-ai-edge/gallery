package com.google.ai.edge.gallery

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.animation.doOnEnd
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.GalleryTheme
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.firebase.analytics.FirebaseAnalytics
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val modelManagerViewModel: ModelManagerViewModel by viewModels()

    private var splashScreenAboutToExit: Boolean = false
    private var contentSet: Boolean = false

    private var firebaseAnalytics: FirebaseAnalytics? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(null)

        firebaseAnalytics = FirebaseAnalytics.getInstance(this)

        // 👉 打印 intent 参数
        intent.extras?.let { extras ->
            for (key in extras.keySet()) {
                Log.d(TAG, "onCreate Extra -> Key: $key, Value: ${extras.get(key)}")
            }
        }

        handleDeepLink(intent)

        // 👉 加载模型列表
        modelManagerViewModel.loadModelAllowlist()

        // 👉 启动 API Server（Foreground Service）
        startApiService()

        val splashScreen = installSplashScreen()

        lifecycleScope.launch {
            delay(1000)
            if (!splashScreenAboutToExit) {
                setMainContent()
            }
        }

        splashScreen.setOnExitAnimationListener { splashScreenView ->
            splashScreenAboutToExit = true

            val now = System.currentTimeMillis()
            val start = splashScreenView.iconAnimationStartMillis
            val duration = splashScreenView.iconAnimationDurationMillis

            val fadeOut = ObjectAnimator.ofFloat(splashScreenView.view, View.ALPHA, 1f, 0f)
            fadeOut.interpolator = DecelerateInterpolator()
            fadeOut.duration = 300L
            fadeOut.doOnEnd { splashScreenView.remove() }

            lifecycleScope.launch {
                val delayTime = duration - (now - start) - 300
                if (delayTime > 0) delay(delayTime)

                setMainContent()
                fadeOut.start()
            }
        }

        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ================================
    // ✅ Compose UI
    // ================================
    private fun setMainContent() {
        if (contentSet) return

        setContent {
            GalleryTheme {
                Surface(modifier = Modifier.fillMaxSize()) {

                    GalleryApp(modelManagerViewModel = modelManagerViewModel)

                    var startMaskFadeout by remember { mutableStateOf(false) }

                    LaunchedEffect(Unit) {
                        startMaskFadeout = true
                    }

                    AnimatedVisibility(
                        visible = !startMaskFadeout,
                        enter = fadeIn(animationSpec = snap(0)),
                        exit = fadeOut(
                            animationSpec = tween(
                                durationMillis = 400,
                                easing = FastOutSlowInEasing
                            )
                        )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background)
                        )
                    }
                }
            }
        }

        @OptIn(ExperimentalApi::class)
        ExperimentalFlags.enableBenchmark = false

        contentSet = true
    }

    // ================================
    // ✅ 启动前台服务
    // ================================
    private fun startApiService() {
        try {
            val intent = Intent(this, ApiServerService::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            Toast.makeText(this, "API Server 启动中（前台服务）", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Log.e(TAG, "start service error: ${e.message}")
            Toast.makeText(this, "启动失败", Toast.LENGTH_SHORT).show()
        }
    }

    // ================================
    // ✅ DeepLink 处理
    // ================================
    private fun handleDeepLink(int

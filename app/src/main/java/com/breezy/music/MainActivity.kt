package com.breezy.music

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.breezy.music.audio.AudioEffects
import com.breezy.music.model.PlaybackState
import com.breezy.music.model.Preferences
import com.breezy.music.model.ThemeMode
import com.breezy.music.model.ThemeState
import com.breezy.music.ui.HomeScreen
import com.breezy.music.ui.WelcomeScreen
import com.breezy.ui.text.BreezyCustomText
import com.breezy.ui.theme.BreezyColorScheme
import com.breezy.ui.theme.BreezyTheme

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or denied, nothing special needed */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ThemeState.load(this)
        PlaybackState.init(this)
        // 音效：载入当前输出设备的档位并同步到播放引擎
        AudioEffects.init(this)
        registerAudioDeviceCallback()

        // 通知权限弹窗
        var showPermissionDialog by mutableStateOf(
            shouldShowNotificationPermissionDialog()
        )

        // 首次启动欢迎引导
        var showWelcome by mutableStateOf(Preferences.isFirstLaunch(this))

        setContent {
            val context = LocalContext.current
            val darkTheme = when (ThemeState.themeMode) {
                ThemeMode.Dark -> true
                ThemeMode.Light -> false
                ThemeMode.FollowSystem -> isSystemInDarkTheme()
                ThemeMode.Monet -> isSystemInDarkTheme()
            }
            val baseScheme = if (darkTheme) BreezyColorScheme.defaultDark()
            else BreezyColorScheme.defaultLight()

            // ── 莫奈取色：从系统壁纸提取动态主题色 ──
            val monetPrimary = if (ThemeState.themeMode == ThemeMode.Monet && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val dynamicScheme = if (darkTheme)
                    dynamicDarkColorScheme(context)
                else
                    dynamicLightColorScheme(context)
                dynamicScheme.primary
            } else null

            val accentColor = monetPrimary ?: ThemeState.accentColor
            val customScheme = baseScheme.copy(
                primary = accentColor,
                brandPrimary = accentColor
            )
            BreezyTheme(darkTheme = darkTheme, colorSchemeOverride = customScheme) {
                if (showWelcome) {
                    WelcomeScreen(
                        onFinish = {
                            showWelcome = false
                            Preferences.setFirstLaunchDone(this@MainActivity)
                        }
                    )
                } else {
                    HomeScreen()
                }

                // 通知权限弹窗
                if (showPermissionDialog) {
                    NotificationPermissionDialog(
                        onYes = {
                            showPermissionDialog = false
                            requestNotificationPermission()
                        },
                        onLater = {
                            showPermissionDialog = false
                        },
                        onNever = {
                            showPermissionDialog = false
                            Preferences.setNeverAskNotification(this@MainActivity, true)
                        }
                    )
                }
            }
        }
    }

    /** 监听音频输出设备变化（插拔耳机 / 连接蓝牙），自动切换音效档位 */
    private fun registerAudioDeviceCallback() {
        val am = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                    AudioEffects.refreshDevice(this@MainActivity)
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                    AudioEffects.refreshDevice(this@MainActivity)
                }
            }, Handler(Looper.getMainLooper()))
        }
    }

    /** 是否应该显示通知权限弹窗 */
    private fun shouldShowNotificationPermissionDialog(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED) return false
        if (Preferences.getNeverAskNotification(this)) return false
        return true
    }

    /** 请求通知权限 */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** 处理来自媒体通知的操作按钮 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        when (intent.action) {
            "prev" -> PlaybackState.playPrevious()
            "next" -> PlaybackState.playNext()
            "playpause" -> PlaybackState.togglePlayPause()
        }
    }

    /** 返回键：简单模式下直接退到后台 */
    override fun onBackPressed() {
        if (Preferences.getBackBehavior(this) == "simple") {
            moveTaskToBack(true)
        } else {
            super.onBackPressed()
        }
    }
}

// ═══════════════════════════════════════════
// 通知权限弹窗
// ═══════════════════════════════════════════

@Composable
private fun NotificationPermissionDialog(
    onYes: () -> Unit,
    onLater: () -> Unit,
    onNever: () -> Unit
) {
    Dialog(onDismissRequest = onLater) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = BreezyTheme.colors.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BreezyCustomText(
                    text = "通知权限",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = BreezyTheme.colors.onSurface
                )

                Spacer(Modifier.height(16.dp))

                BreezyCustomText(
                    text = "本应用需要通知权限来显示播放控制通知，\n建议授予以获得更好的体验。",
                    fontSize = 14.sp,
                    color = BreezyTheme.colors.onSurface.copy(alpha = 0.7f),
                    align = TextAlign.Center
                )

                Spacer(Modifier.height(24.dp))

                // 是（授权）
                Button(
                    onClick = onYes,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BreezyTheme.colors.primary
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    BreezyCustomText("是，授权通知", color = BreezyTheme.colors.onPrimary, fontSize = 15.sp)
                }

                Spacer(Modifier.height(10.dp))

                // 下次再说
                TextButton(onClick = onLater) {
                    BreezyCustomText("下次再说", color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f), fontSize = 14.sp)
                }

                Spacer(Modifier.height(4.dp))

                // 不再提醒
                TextButton(onClick = onNever) {
                    BreezyCustomText("不再提醒", color = BreezyTheme.colors.onSurface.copy(alpha = 0.3f), fontSize = 13.sp)
                }
            }
        }
    }
}

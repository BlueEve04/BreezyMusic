package com.breezy.music.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.breezy.ui.text.BreezyCustomText
import com.breezy.ui.theme.BreezyTheme
import kotlinx.coroutines.launch

/**
 * 欢迎引导页面数据
 */
private data class WelcomePage(
    val icon: ImageVector,
    val title: String,
    val description: String
)

private val welcomePages = listOf(
    WelcomePage(
        icon = Icons.Default.MusicNote,
        title = "欢迎使用 BreezyMusic！",
        description = "由 BlueEve 精心打造\n一款简约优雅的本地音乐播放器\n\n滑动以探索更多"
    ),
    WelcomePage(
        icon = Icons.Default.FolderOpen,
        title = "文件夹管理",
        description = "在文件夹视图中，\n点击文件夹即可浏览歌曲\n\n💡 提示：对文件夹项左滑\n可以快速切换播放策略"
    ),
    WelcomePage(
        icon = Icons.Default.TouchApp,
        title = "快捷操作",
        description = "在任何歌曲列表中\n长按即可进入多选模式\n批量添加到歌单或播放队列\n\n再次长按或点击取消退出"
    ),
    WelcomePage(
        icon = Icons.Default.Album,
        title = "音乐控制中心",
        description = "点击底部音乐 Dock\n或向上拖拽打开控制详情页\n\n← 左滑：播放列表\n→ 右滑：歌词显示"
    )
)

/**
 * 首次启动欢迎引导页面
 * @param onFinish 点击"开始使用"后回调
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WelcomeScreen(onFinish: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { welcomePages.size })
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BreezyTheme.colors.background)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── 跳过按钮 ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 56.dp, end = 24.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                if (pagerState.currentPage < welcomePages.size - 1) {
                    androidx.compose.material3.TextButton(
                        onClick = onFinish
                    ) {
                        BreezyCustomText(
                            "跳过",
                            fontSize = 15.sp,
                            color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            // ── 页面内容 ──
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f)
            ) { page ->
                val item = welcomePages[page]
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // 图标
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .clip(CircleShape)
                            .background(BreezyTheme.colors.primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.title,
                            modifier = Modifier.size(56.dp),
                            tint = BreezyTheme.colors.primary
                        )
                    }

                    Spacer(Modifier.height(40.dp))

                    // 标题
                    BreezyCustomText(
                        text = item.title,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = BreezyTheme.colors.onSurface,
                        align = TextAlign.Center
                    )

                    Spacer(Modifier.height(16.dp))

                    // 描述
                    BreezyCustomText(
                        text = item.description,
                        fontSize = 15.sp,
                        color = BreezyTheme.colors.onSurface.copy(alpha = 0.65f),
                        align = TextAlign.Center
                    )
                }
            }

            // ── 底部：指示器 + 按钮 ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 圆点指示器
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 16.dp)
                ) {
                    repeat(welcomePages.size) { index ->
                        val isSelected = pagerState.currentPage == index
                        val color by animateColorAsState(
                            targetValue = if (isSelected) BreezyTheme.colors.primary
                            else BreezyTheme.colors.onSurface.copy(alpha = 0.2f),
                            animationSpec = tween(300)
                        )
                        Box(
                            modifier = Modifier
                                .size(if (isSelected) 10.dp else 8.dp)
                                .clip(CircleShape)
                                .background(color)
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 最后一页显示"开始使用"
                if (pagerState.currentPage == welcomePages.size - 1) {
                    Button(
                        onClick = onFinish,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 40.dp)
                            .height(50.dp),
                        shape = RoundedCornerShape(25.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BreezyTheme.colors.primary
                        )
                    ) {
                        BreezyCustomText(
                            "开始使用",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                } else {
                    // 下一页按钮
                    Button(
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 40.dp)
                            .height(50.dp),
                        shape = RoundedCornerShape(25.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BreezyTheme.colors.primary
                        )
                    ) {
                        BreezyCustomText(
                            "下一步",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

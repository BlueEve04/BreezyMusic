# BreezyMusic

本地音乐播放器。UI 全部基于自研框架 **BreezyUI**（`../BreezyUI/breezy-ui`，以 Gradle 复合构建引入），播放内核为 **ExoPlayer**，音频链路里挂了一个自写的 `AudioProcessor`。

## 这一版有什么

| 能力 | 说明 |
| --- | --- |
| 运动音乐 | 全库 BPM 分析（解码 → 单声道 → 能量包络 → 自相关），读实时步频，按 BPM 相近度生成定长歌单 |
| 均衡器 | 图示 3–20 段 / 参数均衡（峰值、低架、高架）；预设可锁定、可一键还原；按输出设备分档记忆 |
| 音效 | 前级增益 + 多段 RBJ 双二阶 + Schroeder 混响 + 峰值限压器（阈值可调） |
| 歌词 | LRC 解析与多轨（原文/翻译/次级），手动滑动后延迟回正（延迟可配置）；通过 Super Lyric 的 Binder 接口外发 |
| 歌单 | m3u8 解析支持 BOM/GBK、`#EXTINF`、相对路径、反斜杠与 URL 编码，六级级联匹配并统计未命中 |
| 排序 | 自然序（Track 2 < Track 10）、多字段、稳定 tie-break、拼音分桶 |

## 构建

需要 JDK 17+（本机用 21）、Android SDK（compileSdk 35）。

```bash
# local.properties 写自己的 SDK 路径
echo sdk.dir=/path/to/AndroidSdk > local.properties

./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest   # 31 例单元测试
```

签名：`app/build.gradle.kts` 会读取根目录的 `keystore.properties`（未提供则 release 不签名）。该文件与本仓库无关，**不要提交**。

## 目录

```
app/src/main/java/com/breezy/music/
├── audio/     DSP 处理器、均衡曲线、预设、音效状态
├── model/     数据库、曲库扫描、播放状态、BPM 分析与仓库、步频、运动歌单、歌词发布
├── ui/        主页、播放页、音效面板、歌词页、运动音乐页、设置
└── util/      歌单解析、排序
app/src/test/  纯 JVM 单元测试（不依赖设备）
```

## 说明

- 本项目为个人长期维护的应用，按阶段迭代；仓库里的开发记录在课程工作区，不在此处。
- 曲库文件均为用户本地内容，应用不上传任何音频。

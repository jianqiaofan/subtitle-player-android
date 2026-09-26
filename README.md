# 字幕播放器（Android）

第 1 期：本地字幕学习播放器。在电脑上用[桌面版](https://github.com/jianqiaofan/subtitle-player)下载视频、生成字幕，把**同一文件夹**里的媒体和 `.srt` / `.vtt` 拷到手机，在本 App 里跟读、跳转、编辑字幕并写回文件。

不做：视频下载、Whisper 转写、AI 笔记、账号、联网。

产品规格见 [`AGENTS.md`](AGENTS.md)。

## 当前进度

第 1 期功能已接通：选择学习文件夹、播放、字幕跳转/高亮/跟读循环、倍速、倒计时、复制、编辑字幕并写回文件。

## 如何把电脑文件放到手机

1. 保持媒体和字幕在同一目录，例如：

   ```
   某课程/
     课程名.mp4
     课程名_中文.srt
     课程名_英文.srt
     课程名_同步.srt
   ```

2. 用 USB、网盘或无线传输，拷到手机的 Download、Movies 等目录。
3. 在 App 里点「选择文件夹」，授权该目录后即可列出媒体。

支持的媒体扩展名见 `AGENTS.md` 第 6.1 节。字幕只认外部 `.srt` / `.vtt`。

标签和备注写在同目录的 `字幕文件名.tags.json`（例如 `课程名_中文.srt.tags.json`），不改字幕正文。从电脑拷贝时，把字幕文件和对应的标签文件放在同一文件夹即可互相打开。播放页可以打标签、筛选、同步标签文件；「批量同步标签」一次处理一个视频文件夹（含子文件夹）里的多部视频。

## 在 Android Studio 里运行

1. 安装 [Android Studio](https://developer.android.com/studio)（需 Android SDK，compileSdk 37）。
2. 打开本仓库根目录，等待 Gradle 同步。
3. 用 USB 连接 Redmi Note 13 Pro（或模拟器），运行 `app`。

包名：`com.jianqiaofan.subtitleplayer`  
最低系统：Android 8.0（API 26）  
不申请联网权限。

## 测试夹具

- 单元测试用短 SRT：`app/src/test/resources/subtitles/sample.srt`（交接文档 6.4 节）。
- 真机验收请自行把完整视频拷到手机。仓库忽略视频等媒体文件，只保留字幕文本。本地可参考 `测试视频字幕/` 里的 `.srt`（`.mp4` 不会进 git）。

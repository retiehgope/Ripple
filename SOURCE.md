# 源码结构 / Source layout

```
src\                    插件主体（编译进 .spmod）
  SpwConvolve.java      卷积引擎 + BASS/JNA 绑定 + 通道扫描
  MainPlugin.java       PF4J 插件入口（start/stop + 配置回调）
  SelfTest.java         离线算法自测（不出声，5 项）
tools\                  开发期验证工具（不打包）
  TestFinder.java       用宿主自己的描述符查找器验证插件包
  TestManifest.java     用宿主自己的 manifest 读取器验证 .spmod
  TestWav.java          验证各种 WAV 格式解析
plugin-resources\
  preference_config.json  插件配置界面定义
build-plugin.ps1        构建 .jar 与 .spmod
promo\                  工坊简介、封面 prompt
```

## 构建 / Build

需要 JDK 25，以及 Salt Player 安装目录下的 `app\ffmpeg-x64.dll`（宿主 fat jar，提供 API/JNA classpath）。

```
.\build-plugin.ps1
```

产物：`build\Ripple.jar`（本地导入）、`build\Ripple.spmod`（创意工坊发布）。

> 打包必须用 JDK `jar` 工具：.NET 的 ZipFile 在 Windows 上写反斜杠条目，Java 端会找不到 `META-INF/MANIFEST.MF`。

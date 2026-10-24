<#
    构建 .spmod 插件包（PowerShell 5.1 兼容）

    产物：build\SpwConv.spmod
          —— 本质是带 PF4J 清单的 jar；宿主接受 .spmod / .jar / .zip

    用法：.\build-plugin.ps1
#>
$ErrorActionPreference = 'Stop'
$base  = $PSScriptRoot
$hostDir = 'C:\Program Files (x86)\Steam\steamapps\common\Salt Player for Windows'
$fat   = Join-Path $hostDir 'app\ffmpeg-x64.dll'   # 伪装成 DLL 的 fat jar（含 API + JNA + PF4J）
$out   = Join-Path $base 'build'
$cls   = Join-Path $out 'plugin-classes'
$res   = Join-Path $base 'plugin-resources'
$jarPath = Join-Path $out 'Ripple.spmod'
$manifest = Join-Path $out 'PLUGIN.MF'

if (-not (Test-Path $fat)) { throw "找不到宿主 fat jar：$fat" }

# ---- 找 javac ----
$javac = $null
$tmpJdk = 'C:\Users\Lawrence Yao\dsh\tmp\jdk'
if (Test-Path $tmpJdk) {
    $hit = Get-ChildItem $tmpJdk -Recurse -Filter javac.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($hit) { $javac = $hit.FullName }
}
if (-not $javac) {
    $cmd = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($cmd) { $javac = $cmd.Source }
}
if (-not $javac) { throw '找不到 javac（需要 JDK 25）' }
$jarTool = Join-Path (Split-Path $javac) 'jar.exe'

Write-Host "javac : $javac"
Write-Host "宿主  : $fat"

# ---- 编译 ----
if (Test-Path $cls) { Remove-Item $cls -Recurse -Force }
New-Item -ItemType Directory -Force -Path $cls | Out-Null

$sources = @(
    (Join-Path $base 'src\SpwConvolve.java'),
    (Join-Path $base 'src\MainPlugin.java')
)
& $javac -encoding UTF-8 -nowarn -cp $fat -d $cls $sources
if ($LASTEXITCODE -ne 0) { throw "javac 失败（exit $LASTEXITCODE）" }

# ---- 清单（必须无 BOM，jar 才能解析） ----
$mf = @(
    'Manifest-Version: 1.0',
    'Plugin-Id: com.ripple.spw',
    'Plugin-Class: com.local.spwconv.MainPlugin',
    'Plugin-Version: 1.0.0',
    'Plugin-Provider: Ripple',
    'Plugin-Name: Ripple',
    'Plugin-Description: Convolution filter plugin for Salt Player',
    'Plugin-Open-Source-Url: https://github.com/retiehgope/Ripple',
    'Plugin-Has-Config: true'
)
[System.IO.File]::WriteAllLines($manifest, $mf, (New-Object System.Text.ASCIIEncoding))

# ---- 打包 ----
# 中间 jar 必须是独立路径，否则会被后面的 .spmod 覆盖
$inter = Join-Path $out 'intermediate'
New-Item -ItemType Directory -Force -Path $inter | Out-Null
$jarPath = Join-Path $inter 'Ripple.jar'
if (Test-Path $jarPath) { Remove-Item $jarPath -Force }
& $jarTool cfm $jarPath $manifest -C $cls .
if ($LASTEXITCODE -ne 0) { throw "jar 打包失败（exit $LASTEXITCODE）" }

& $jarTool uf $jarPath -C $res preference_config.json
if ($LASTEXITCODE -ne 0) { throw "追加配置界面失败（exit $LASTEXITCODE）" }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
$entries = $zip.Entries.Count
$zip.Dispose()

$sizeKB = [math]::Round((Get-Item $jarPath).Length / 1KB, 1)
Write-Host ''
Write-Host "中间 jar: $jarPath  ($sizeKB KB, $entries entries)"

# ---- 打包 .spmod ----
# 结构（实测宿主的读取逻辑）：
#   根 META-INF/MANIFEST.MF   ← 宿主 readManifestFromZip 找这个
#   classes/                  ← 官方 Gradle 结构，展开后插件根
#   lib/                      ← 运行时依赖 jar
$stage = Join-Path $out 'spmod-stage'
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path (Join-Path $stage 'classes') | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $stage 'lib') | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $stage 'META-INF') | Out-Null
[System.IO.Compression.ZipFile]::ExtractToDirectory($jarPath, (Join-Path $stage 'classes'))
Copy-Item $manifest (Join-Path $stage 'META-INF\MANIFEST.MF') -Force

$spmod = Join-Path $out 'Ripple.spmod'
if (Test-Path $spmod) { Remove-Item $spmod -Force }
# 必须用 jar 工具打包：.NET 的 ZipFile 在 Windows 上把分隔符写成反斜杠，
# Java 端 getEntry("META-INF/MANIFEST.MF") 会找不到（实测踩过）
& $jarTool cfM $spmod -C $stage .
if ($LASTEXITCODE -ne 0) { throw "spmod 打包失败（exit $LASTEXITCODE）" }

# 本地导入也用它（.jar 直连最稳）
$jarCopy = Join-Path $out 'Ripple.jar'
Copy-Item $jarPath $jarCopy -Force

$zsp = [System.IO.Compression.ZipFile]::OpenRead($spmod)
$spNames = ($zsp.Entries | Select-Object -First 5 | ForEach-Object { $_.FullName }) -join ', '
$spCount = $zsp.Entries.Count
$zsp.Dispose()

Write-Host ''
Write-Host "OK  $spmod  ($([math]::Round((Get-Item $spmod).Length/1KB,1)) KB, $spCount entries)" -ForegroundColor Green
Write-Host "    $spNames ..."
Write-Host "OK  $jarCopy  —— 本地导入用这个" -ForegroundColor Green
Write-Host ''
Write-Host '安装（本地）：创意工坊 → 模组管理 → 导入模组 → 选 Ripple.jar'
Write-Host '发布（Steam）：创意工坊创作中心只接受 .spmod，用上面那个 Ripple.spmod'
Write-Host '说明：本插件只用 JNA 直调 bass.dll，不申请 CLASS_TRANSFORM 权限。'

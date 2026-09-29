# 打包已签名正式包，并调用桌面仓库的 publish-android.ps1 上传。
# 用法见仓库根目录的 发布更新.md。不要在本脚本里写签名密码。

param(
    [string]$Notes = "",
    [string]$VersionName = "",
    [string]$DesktopRepo = "",
    [switch]$UploadOnly
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$gradlePath = Join-Path $root "app\build.gradle.kts"
$apkPath = Join-Path $root "app\build\outputs\apk\release\app-release.apk"
$utf8 = New-Object System.Text.UTF8Encoding $false

function Fail([string]$message) {
    [Console]::Error.WriteLine($message)
    exit 1
}

function Resolve-DesktopRepo {
    $configured = ""
    if ($DesktopRepo.Trim()) {
        $configured = $DesktopRepo.Trim()
    } elseif ($env:SUBTITLE_DESKTOP_REPO) {
        $configured = $env:SUBTITLE_DESKTOP_REPO.Trim()
    } else {
        $localFile = Join-Path $root "publish.local.properties"
        if (Test-Path $localFile) {
            foreach ($line in [System.IO.File]::ReadAllLines($localFile)) {
                if ($line -match '^\s*desktopRepo\s*=\s*(.+)\s*$') {
                    $configured = $Matches[1].Trim().Trim('"')
                    break
                }
            }
        }
    }
    if ($configured) {
        if (-not (Test-Path $configured)) { Fail "桌面仓库不存在：$configured" }
        return $configured
    }
    $sibling = Join-Path (Split-Path -Parent $root) "subtitle-player"
    if (-not (Test-Path (Join-Path $sibling ".git"))) {
        Write-Output "正在克隆桌面仓库到 $sibling"
        & git clone https://github.com/jianqiaofan/subtitle-player.git $sibling
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path (Join-Path $sibling ".git"))) {
            Fail "克隆桌面仓库失败。"
        }
    }
    return $sibling
}

function Install-PublishScript([string]$desktop) {
    $source = Join-Path $PSScriptRoot "publish-android.ps1"
    if (-not (Test-Path $source)) { Fail "缺少 $source" }
    $destDir = Join-Path $desktop "subtitle_server\deploy"
    New-Item -ItemType Directory -Force -Path $destDir | Out-Null
    $dest = Join-Path $destDir "publish-android.ps1"
    Copy-Item -Path $source -Destination $dest -Force
    return $dest
}

function Get-SdkDir {
    $file = Join-Path $root "local.properties"
    if (-not (Test-Path $file)) { return "" }
    foreach ($line in [System.IO.File]::ReadAllLines($file)) {
        if ($line -match '^\s*sdk\.dir\s*=\s*(.+)\s*$') {
            $value = $Matches[1].Trim()
            return ($value -replace '\\\\', '\' -replace '\\:', ':')
        }
    }
    return ""
}

function Find-Aapt {
    $sdk = Get-SdkDir
    if (-not $sdk) { return "" }
    $tools = Join-Path $sdk "build-tools"
    if (-not (Test-Path $tools)) { return "" }
    $found = Get-ChildItem $tools -Filter "aapt.exe" -Recurse -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending |
        Select-Object -First 1
    if ($found) { return $found.FullName }
    return ""
}

function Read-GradleVersion([string]$text) {
    if ($text -notmatch 'versionCode\s*=\s*(\d+)') { Fail "app/build.gradle.kts 里找不到 versionCode。" }
    $code = [int]$Matches[1]
    if ($text -notmatch 'versionName\s*=\s*"([^"]+)"') { Fail "app/build.gradle.kts 里找不到 versionName。" }
    return @{ Code = $code; Name = $Matches[1] }
}

function Get-NextVersionName([string]$current) {
    if ($current -notmatch '^(.*?)(\d+)$') {
        Fail "无法从 $current 自动递增版本名，请传入 -VersionName。"
    }
    $prefix = $Matches[1]
    $number = [int]$Matches[2] + 1
    return "$prefix$number"
}

function Assert-ApkVersion([string]$aapt, [string]$apk, [int]$code, [string]$name) {
    $output = & $aapt dump badging $apk
    $line = $output | Where-Object { $_ -like "package:*" } | Select-Object -First 1
    if (-not $line) { Fail "无法从 APK 读出版本号，已取消上传。" }
    if ($line -notmatch "versionCode='(\d+)'") { Fail "无法从 APK 读出 versionCode，已取消上传。" }
    $apkCode = [int]$Matches[1]
    if ($line -notmatch "versionName='([^']*)'") { Fail "无法从 APK 读出 versionName，已取消上传。" }
    $apkName = $Matches[1]
    if ($apkCode -ne $code -or $apkName -ne $name) {
        Fail "APK 版本是 $apkName ($apkCode)，工程里是 $name ($code)。已取消上传。"
    }
}

if (-not $Notes.Trim()) { Fail "请用 -Notes 写明这次更新做了什么。" }

$desktop = Resolve-DesktopRepo
$publishScript = Install-PublishScript $desktop

$aapt = Find-Aapt
if (-not $aapt) { Fail "找不到 Android SDK 的 aapt.exe。请确认 local.properties 里的 sdk.dir，并安装 build-tools。" }

$gradleText = [System.IO.File]::ReadAllText($gradlePath)
$current = Read-GradleVersion $gradleText

if ($UploadOnly) {
    if (-not (Test-Path $apkPath)) { Fail "还没有正式包。请去掉 -UploadOnly 重新打包。" }
    $versionName = $current.Name
    Assert-ApkVersion $aapt $apkPath $current.Code $versionName
    $versionCode = $current.Code
} else {
    $versionCode = $current.Code + 1
    $versionName = if ($VersionName.Trim()) { $VersionName.Trim() } else { Get-NextVersionName $current.Name }
    $updated = [regex]::Replace($gradleText, 'versionCode\s*=\s*\d+', "versionCode = $versionCode", 1)
    $updated = [regex]::Replace($updated, 'versionName\s*=\s*"[^"]*"', "versionName = `"$versionName`"", 1)
    $savedBytes = [System.IO.File]::ReadAllBytes($gradlePath)
    [System.IO.File]::WriteAllText($gradlePath, $updated, $utf8)
    Push-Location $root
    try {
        & .\gradlew.bat :app:assembleRelease
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path $apkPath)) {
            [System.IO.File]::WriteAllBytes($gradlePath, $savedBytes)
            Fail "打包失败，已恢复版本号。"
        }
    } finally {
        Pop-Location
    }
    Assert-ApkVersion $aapt $apkPath $versionCode $versionName
}

Push-Location $desktop
try {
    & powershell.exe -NoProfile -File ".\subtitle_server\deploy\publish-android.ps1" -Apk $apkPath -VersionCode $versionCode -VersionName $versionName -Notes $Notes
    if ($LASTEXITCODE -ne 0) {
        Fail "上传失败，版本号已保留为 $versionName ($versionCode)。安装包在 $apkPath。修好后执行：powershell -File scripts\publish-release.ps1 -UploadOnly -Notes `"$Notes`""
    }
} finally {
    Pop-Location
}

Write-Output "已上传 versionName=$versionName versionCode=$versionCode"
Write-Output "手机或平板打开账号页，点「安装更新」。"

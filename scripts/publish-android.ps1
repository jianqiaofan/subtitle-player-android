# 计算 SHA-256，写 latest.json，并上传到已经配好的更新目录。
# 由 scripts/publish-release.ps1 复制到桌面仓库 subtitle_server\deploy\ 后执行。
# 不修改 Nginx、数据库，也不写签名密码。

param(
    [string]$Apk = "",
    [int]$VersionCode = 0,
    [string]$VersionName = "",
    [string]$Notes = ""
)

$ErrorActionPreference = "Stop"
$SshHost = "118.25.45.22"
$RemoteDir = "/var/www/subtitle-releases"
$RemoteStage = "/tmp/subtitle-android-publish"
$PublicApkUrl = "https://subtitle.gcsfg.work/releases/app.apk"
$PublicManifestUrl = "https://subtitle.gcsfg.work/releases/latest.json"
$utf8 = New-Object System.Text.UTF8Encoding $false

function Fail([string]$message) {
    [Console]::Error.WriteLine($message)
    exit 1
}

function Escape-Json([string]$value) {
    $builder = New-Object System.Text.StringBuilder
    foreach ($ch in $value.ToCharArray()) {
        switch ($ch) {
            '"' { [void]$builder.Append('\"') }
            '\' { [void]$builder.Append('\\') }
            "`n" { [void]$builder.Append('\n') }
            "`r" { [void]$builder.Append('\r') }
            "`t" { [void]$builder.Append('\t') }
            default {
                $code = [int]$ch
                if ($code -lt 32) {
                    [void]$builder.Append(("\u{0:x4}" -f $code))
                } else {
                    [void]$builder.Append($ch)
                }
            }
        }
    }
    return $builder.ToString()
}

if (-not $Apk -or -not (Test-Path $Apk)) { Fail "找不到安装包：$Apk" }
if ($VersionCode -lt 1) { Fail "VersionCode 必须大于 0。" }
if (-not $VersionName.Trim()) { Fail "请传入 VersionName。" }
if (-not $Notes.Trim()) { Fail "请传入 Notes。" }

try {
    $remoteManifest = Invoke-RestMethod -Uri $PublicManifestUrl -TimeoutSec 20
    $remoteCode = [int]$remoteManifest.versionCode
} catch {
    Fail "无法读取服务器上的版本说明，已取消上传。"
}
if ($VersionCode -le $remoteCode) {
    Fail "服务器上已是 versionCode $remoteCode。本次 $VersionCode 没有更大，已取消上传。"
}

$hash = (Get-FileHash -Algorithm SHA256 -Path $Apk).Hash.ToLowerInvariant()
$json = @"
{
  "versionCode": $VersionCode,
  "versionName": "$(Escape-Json $VersionName)",
  "url": "$PublicApkUrl",
  "sha256": "$hash",
  "notes": "$(Escape-Json $Notes)"
}
"@
$tempDir = Join-Path $env:TEMP "subtitle-android-publish"
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
$jsonPath = Join-Path $tempDir "latest.json"
[System.IO.File]::WriteAllText($jsonPath, $json.Trim() + "`n", $utf8)

& ssh.exe -o BatchMode=yes $SshHost "rm -rf $RemoteStage && mkdir -p $RemoteStage"
if ($LASTEXITCODE -ne 0) { Fail "无法连接更新服务器。" }

& scp.exe -o BatchMode=yes $Apk "${SshHost}:${RemoteStage}/app.apk"
if ($LASTEXITCODE -ne 0) { Fail "安装包上传失败。" }

& scp.exe -o BatchMode=yes $jsonPath "${SshHost}:${RemoteStage}/latest.json"
if ($LASTEXITCODE -ne 0) { Fail "版本说明上传失败。服务器上的 latest.json 尚未替换。" }

$install = "sudo cp $RemoteStage/app.apk $RemoteDir/app.apk && sudo cp $RemoteStage/latest.json $RemoteDir/latest.json && sudo chmod 644 $RemoteDir/app.apk $RemoteDir/latest.json && rm -rf $RemoteStage"
& ssh.exe -o BatchMode=yes $SshHost $install
if ($LASTEXITCODE -ne 0) { Fail "无法把更新文件放进发布目录。" }

$remoteHashLine = & ssh.exe -o BatchMode=yes $SshHost "sha256sum $RemoteDir/app.apk"
if ($LASTEXITCODE -ne 0) { Fail "无法核对服务器上的安装包。" }
$remoteHash = ($remoteHashLine | Select-Object -First 1).ToString().Split(" ")[0].ToLowerInvariant()
if ($remoteHash -ne $hash) { Fail "服务器上的安装包哈希不一致，请不要让手机安装这一版。" }

try {
    $published = Invoke-RestMethod -Uri $PublicManifestUrl -TimeoutSec 20
} catch {
    Fail "文件已复制，但无法再次读取版本说明。"
}
if ([int]$published.versionCode -ne $VersionCode -or $published.sha256 -ne $hash) {
    Fail "公开的版本说明与本次上传不一致。"
}

Write-Output "已发布 versionName=$VersionName versionCode=$VersionCode"

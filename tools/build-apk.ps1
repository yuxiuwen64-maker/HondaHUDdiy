# Copyright (C) 2026 yuxiuwen64-maker
# SPDX-License-Identifier: AGPL-3.0-or-later
param(
    [Parameter(Mandatory = $true)][string]$Module,
    [Parameter(Mandatory = $true)][string]$Package,
    [string]$OutName = ""
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot

function Find-Sdk {
    $candidates = @()
    if ($env:ANDROID_SDK_ROOT) { $candidates += $env:ANDROID_SDK_ROOT }
    if ($env:ANDROID_HOME) { $candidates += $env:ANDROID_HOME }
    $candidates += @(
        "$env:LOCALAPPDATA\Android\Sdk",
        "$env:USERPROFILE\AppData\Local\Android\Sdk"
    )
    foreach ($c in $candidates) {
        if ($c -and (Test-Path (Join-Path $c "build-tools"))) { return $c }
    }
    throw "找不到 Android SDK，请设置 ANDROID_SDK_ROOT"
}

function Find-JavaHome {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $candidates += @(
        "C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot",
        "C:\Program Files\Microsoft\jdk-21.0.6.101-hotspot",
        "C:\Program Files\Android\Android Studio\jbr",
        "C:\Program Files\Java\jdk-17"
    )
    foreach ($c in $candidates) {
        if ($c -and (Test-Path (Join-Path $c "bin\javac.exe"))) { return $c }
    }
    throw "找不到 JDK，请设置 JAVA_HOME"
}

$sdk = Find-Sdk
$bt = Get-ChildItem (Join-Path $sdk "build-tools") -Directory |
    Sort-Object { [int]($_.Name.Split('.')[0]) } -Descending |
    Select-Object -First 1
if (-not $bt) { throw "SDK 中没有 build-tools" }
$bt = $bt.FullName

$platforms = Get-ChildItem (Join-Path $sdk "platforms") -Directory |
    Sort-Object Name -Descending
if (-not $platforms) { throw "SDK 中没有 platforms" }
$androidJar = Join-Path $platforms[0].FullName "android.jar"

$javaHome = Find-JavaHome

$mod = Join-Path $root $Module
$build = Join-Path $mod "build-manual"
if (Test-Path $build) { Remove-Item -Recurse -Force $build }
New-Item -ItemType Directory -Force -Path $build, "$build\gen", "$build\classes", "$build\dex" | Out-Null

if (-not $OutName) { $OutName = Split-Path $mod -Leaf }

# 1. 资源编译
& "$bt\aapt2.exe" compile --dir "$mod\src\main\res" -o "$build\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

# 2. manifest 注入 package（Gradle 用 namespace，aapt2 需要 package 属性）
$manifest = [System.IO.File]::ReadAllText("$mod\src\main\AndroidManifest.xml", [System.Text.Encoding]::UTF8)
$manifest = $manifest -replace "<manifest ", "<manifest package=`"$Package`" "
[System.IO.File]::WriteAllText("$build\AndroidManifest.xml", $manifest, (New-Object System.Text.UTF8Encoding $false))

# 3. 链接资源 + 生成 R.java
& "$bt\aapt2.exe" link -o "$build\base.apk" -I $androidJar --manifest "$build\AndroidManifest.xml" --java "$build\gen" --min-sdk-version 26 --target-sdk-version 29 --version-code 8 --version-name "1.7" "$build\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

# 4. 编译 Java
$sources = @()
$sources += Get-ChildItem "$mod\src\main\java" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$sources += Get-ChildItem "$build\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& "$javaHome\bin\javac.exe" -encoding UTF-8 -nowarn -source 8 -target 8 -classpath $androidJar -d "$build\classes" @sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

# 5. dex
$classes = Get-ChildItem "$build\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& "$bt\d8.bat" --min-api 26 --lib $androidJar --output "$build\dex" @classes
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

# 6. 把 classes.dex 塞进 apk
$python = $env:DSH_PYTHON
if (-not $python) { $python = "python" }
& $python "$PSScriptRoot\add_dex.py" "$build\base.apk" "$build\dex\classes.dex" "$build\unsigned.apk"
if ($LASTEXITCODE -ne 0) { throw "add dex failed" }

# 7. 对齐 + 签名
& "$bt\zipalign.exe" -f 4 "$build\unsigned.apk" "$build\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

$ks = Join-Path $root "tools\debug.keystore"
if (-not (Test-Path $ks)) {
    & "$javaHome\bin\keytool.exe" -genkeypair -keystore $ks -storepass android -keypass android `
        -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -validity 10000 | Out-Null
}

$dist = Join-Path $root "dist"
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$final = Join-Path $dist "$OutName.apk"
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --ks-key-alias androiddebugkey --out $final "$build\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

& "$bt\apksigner.bat" verify $final
if ($LASTEXITCODE -ne 0) { throw "verify failed" }

"{0} -> {1} ({2:N0} bytes)" -f $Module, $final, (Get-Item $final).Length






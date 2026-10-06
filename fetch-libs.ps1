# Fetch the local-only build inputs for the voice add-on (warehouse-keeper-voice).
#
#   1) sherpa-onnx Java API       sherpa-onnx-jvm-1.13.8.jar                 (~0.2 MB)
#   2) sherpa-onnx Windows x64    sherpa-onnx-native-lib-win-x64-1.13.8.jar   (~7.9 MB,
#      contains onnxruntime.dll + sherpa-onnx-jni.dll)
#   3) Chinese Paraformer model   sherpa-onnx-paraformer-zh-small-2024-03-09  (~74 MB)
#      -> model.int8.onnx + tokens.txt
#   4) Chinese Zipformer model    sherpa-onnx-zipformer-ctc-zh-int8-2025-07-03 (~330 MB)
#      -> model.int8.onnx + tokens.txt + bbpe.model
#
# The two models are staged as jar resources under libs\, so they ship inside the add-on jar.
# Everything lands in libs\ which is git-ignored, so a fresh clone must run this once before
# building.  Usage:
#
#   powershell -ExecutionPolicy Bypass -File fetch-libs.ps1
#
# None of this is personal data: all four are public downloads.

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$libs = Join-Path $root 'libs'
$cache = Join-Path $env:TEMP 'warehouse-keeper-voice'
New-Item -ItemType Directory -Force -Path $libs, $cache | Out-Null

$rel = 'https://github.com/k2-fsa/sherpa-onnx/releases/download'
$jvmUrl = "$rel/v1.13.8/sherpa-onnx-jvm-1.13.8.jar"
$nativeUrl = "$rel/v1.13.8/sherpa-onnx-native-lib-win-x64-1.13.8.jar"
$paraformerUrl = "$rel/asr-models/sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2"
$zipformerUrl = "$rel/asr-models/sherpa-onnx-zipformer-ctc-zh-int8-2025-07-03.tar.bz2"

function Get-File($url, $dest, $minBytes) {
    if (Test-Path $dest) {
        $have = (Get-Item $dest).Length
        if ($have -ge $minBytes) { Write-Host "  cached  $dest  ($([math]::Round($have / 1MB, 1)) MB)"; return }
        Write-Host "  re-download (cached copy looks truncated)"
        Remove-Item $dest -Force
    }
    Write-Host "  download $url"
    Invoke-WebRequest -Uri $url -OutFile $dest -TimeoutSec 3600
    $len = (Get-Item $dest).Length
    if ($len -lt $minBytes) { throw "download too small: $dest = $len bytes" }
    Write-Host "  got      $([math]::Round($len / 1MB, 1)) MB"
}

function Expand-Model($tarPath, $innerName, $stagedDir, [string[]]$files) {
    $tmp = Join-Path $cache ('x-' + [System.IO.Path]::GetFileNameWithoutExtension($tarPath))
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    & tar.exe -xjf $tarPath -C $tmp
    if ($LASTEXITCODE -ne 0) { throw "tar failed with exit code $LASTEXITCODE" }
    $inner = Join-Path $tmp $innerName
    if (-not (Test-Path $inner)) { throw "unexpected model archive layout under $tmp" }
    New-Item -ItemType Directory -Force -Path $stagedDir | Out-Null
    foreach ($f in $files) {
        Copy-Item (Join-Path $inner $f) (Join-Path $stagedDir $f) -Force
    }
    $sum = (Get-ChildItem $stagedDir -File | Measure-Object -Property Length -Sum).Sum
    Write-Host "  staged   $stagedDir  ($([math]::Round($sum / 1MB, 1)) MB)"
}

Write-Host '[1/4] sherpa-onnx Java API jar'
Get-File $jvmUrl (Join-Path $libs 'sherpa-onnx-jvm-1.13.8.jar') 150000

Write-Host '[2/4] sherpa-onnx Windows x64 native jar'
Get-File $nativeUrl (Join-Path $libs 'native-lib-win-x64.jar') 6000000

Write-Host '[3/4] Chinese Paraformer small model (primary)'
$paraTar = Join-Path $cache 'sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2'
Get-File $paraformerUrl $paraTar 50000000
Expand-Model $paraTar 'sherpa-onnx-paraformer-zh-small-2024-03-09' `
    (Join-Path $libs 'sherpa-model-res\warehouse-keeper-voice-sherpa') @('model.int8.onnx', 'tokens.txt')

Write-Host '[4/4] Chinese Zipformer-CTC int8 model'
$zipTar = Join-Path $cache 'sherpa-onnx-zipformer-ctc-zh-int8-2025-07-03.tar.bz2'
Get-File $zipformerUrl $zipTar 200000000
Expand-Model $zipTar 'sherpa-onnx-zipformer-ctc-zh-int8-2025-07-03' `
    (Join-Path $libs 'sherpa-model-res-zipformer\warehouse-keeper-voice-sherpa-zipformer') `
    @('model.int8.onnx', 'tokens.txt', 'bbpe.model')

Write-Host 'done - place this add-on at <warehouse-keeper>/voice and run:  gradlew :voice:build'

# 编译 Windows 写入受限令牌运行器。
#
# 只在 Windows 上有效；其他平台直接返回 0，因为 OsSandbox 在 macOS/Linux 走 Seatbelt 和 bwrap。
# 输出到 .pilot/bin/pilot-sandbox.exe（已被 .gitignore 忽略），start.ps1 编译成功后通过
# PILOT_WINDOWS_SANDBOX_RUNNER 传给应用，OsSandbox.windowsRunner 会读这个环境变量。
param(
    [string]$Output,
    [switch]$Force
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    Write-Host 'Pilot: not Windows, skipping the sandbox runner build.'
    exit 0
}
$source = Join-Path $root 'pilot-core/src/windows/pilot-sandbox.c'
if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
    Write-Host "Pilot: sandbox runner source is missing: $source"
    exit 1
}
$target = if ($Output) { $Output } else { Join-Path $root '.pilot/bin/pilot-sandbox.exe' }
if (-not $Force -and (Test-Path -LiteralPath $target -PathType Leaf)) {
    $built = (Get-Item -LiteralPath $target).LastWriteTimeUtc
    if ($built -ge (Get-Item -LiteralPath $source).LastWriteTimeUtc) {
        Write-Host "Pilot: sandbox runner is up to date: $target"
        exit 0
    }
}
# 只支持 MinGW gcc：cl.exe 需要另配 Windows SDK 环境，缺工具时如实说明而不是静默跳过。
$compiler = Get-Command gcc.exe -ErrorAction SilentlyContinue
if (-not $compiler) {
    Write-Host 'Pilot: gcc.exe not found, the Windows sandbox runner was not built. Commands run locally without write isolation.'
    exit 1
}
New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
& $compiler.Source -Wall -Wextra -O2 -o $target $source -ladvapi32
if ($LASTEXITCODE -ne 0) {
    Write-Host "Pilot: building the sandbox runner failed with exit code $LASTEXITCODE."
    exit $LASTEXITCODE
}
Write-Host "Pilot: sandbox runner built: $target"
exit 0
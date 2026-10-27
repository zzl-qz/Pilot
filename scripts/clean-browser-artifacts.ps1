<#
  清理 playwright-cli 在项目里留下的调试产物：页面快照 page-*.yml、控制台日志 console-*.log、
  trace 与录屏都写在项目根的 .playwright-cli/ 下。这些文件是每次运行重新生成的中间产物。

  幂等：没有产物时什么都不做，返回 0，可以安全地放在提交钩子或收尾步骤里反复调用。

  用法：
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/clean-browser-artifacts.ps1
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/clean-browser-artifacts.ps1 -Quiet
#>
param(
    [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path,
    [switch]$Quiet
)

$target = Join-Path $Root '.playwright-cli'

if (-not (Test-Path -LiteralPath $target)) {
    if (-not $Quiet) { Write-Host '没有浏览器调试产物，无需清理。' }
    return
}

$files = @(Get-ChildItem -LiteralPath $target -Recurse -File -ErrorAction SilentlyContinue)
$size = ($files | Measure-Object -Property Length -Sum).Sum
if ($null -eq $size) { $size = 0 }

Remove-Item -LiteralPath $target -Recurse -Force -ErrorAction SilentlyContinue

if (-not $Quiet) {
    Write-Host ('已清理浏览器调试产物：{0} 个文件，{1:N1} KB' -f $files.Count, ($size / 1KB))
}

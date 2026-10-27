<#
  playwright-cli 的包装：会话结束时自动清理调试产物。

  用法与 playwright-cli 完全一致，只是前面换成这个脚本：
    powershell -NoProfile -File scripts/pw.ps1 open http://127.0.0.1:8080/
    powershell -NoProfile -File scripts/pw.ps1 click e3
    powershell -NoProfile -File scripts/pw.ps1 close      # 这一步之后自动清理

  close / close-all / kill-all 都会触发清理；其它命令原样透传，退出码保持一致。
#>
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$CliArgs
)

& playwright-cli @CliArgs
$code = $LASTEXITCODE

if ($CliArgs -and $CliArgs.Count -gt 0 -and $CliArgs[0] -in @('close', 'close-all', 'kill-all')) {
    & (Join-Path $PSScriptRoot 'clean-browser-artifacts.ps1') -Quiet
}

exit $code

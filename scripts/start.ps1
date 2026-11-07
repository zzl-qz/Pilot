param([Parameter(ValueFromRemainingArguments=$true)][string[]]$PilotArgs)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $root
if ($env:SystemRoot) {
    $socketTemp = Join-Path $env:SystemRoot 'Temp'
    if ((Test-Path -LiteralPath $socketTemp -PathType Container) -and $env:JAVA_TOOL_OPTIONS -notmatch '(^|\s)-Djdk\.net\.unixdomain\.tmpdir(?:=|\s)') {
        $socketOption = "-Djdk.net.unixdomain.tmpdir=$socketTemp"
        $env:JAVA_TOOL_OPTIONS = if ([string]::IsNullOrWhiteSpace($env:JAVA_TOOL_OPTIONS)) {
            $socketOption
        } else {
            "$env:JAVA_TOOL_OPTIONS $socketOption"
        }
    }
}
$manifest = @{}
Get-Content -LiteralPath (Join-Path $root 'pilot-core/src/main/resources/bootstrap/runtime.properties') | ForEach-Object {
    if ($_ -match '^([^#=]+)=(.*)$') { $manifest[$matches[1]] = $matches[2] }
}
$cache = if ($env:PILOT_BOOTSTRAP_DIR) { $env:PILOT_BOOTSTRAP_DIR } elseif ($env:PILOT_DATA_DIR) { Join-Path $env:PILOT_DATA_DIR 'bootstrap' } else { Join-Path $root '.pilot/bootstrap' }
function Test-PilotJava([string]$Command) {
    try {
        $info = New-Object System.Diagnostics.ProcessStartInfo
        $info.FileName = $Command
        $info.Arguments = '-XshowSettings:properties -version'
        $info.UseShellExecute = $false
        $info.CreateNoWindow = $true
        $info.RedirectStandardOutput = $true
        $info.RedirectStandardError = $true
        $process = New-Object System.Diagnostics.Process
        $process.StartInfo = $info
        [void]$process.Start()
        $out = $process.StandardOutput.ReadToEndAsync()
        $err = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(8000)) { $process.Kill(); return $null }
        $details = $out.GetAwaiter().GetResult() + $err.GetAwaiter().GetResult()
        $code = $process.ExitCode
        $process.Dispose()
        if ($code -ne 0 -or $details -notmatch 'java.specification.version\s*=\s*([0-9]+)' -or [int]$matches[1] -lt 25) { return $null }
        if ($details -notmatch 'java.home\s*=\s*([^\r\n]+)') { return $null }
        $javaHome = $matches[1].Trim()
        if (-not (Test-Path -LiteralPath (Join-Path $javaHome 'bin/javac.exe'))) { return $null }
        if ($details -notmatch 'os.arch\s*=\s*(amd64|x86_64)') { return $null }
        return $javaHome
    } catch { return $null }
}
$selected = $null
if ($env:PILOT_FORCE_MANAGED_JAVA -ne 'true') {
    if ($env:JAVA_HOME) { $selected = Test-PilotJava (Join-Path $env:JAVA_HOME 'bin/java.exe') }
    if (-not $selected) { $selected = Test-PilotJava 'java.exe' }
}
if (-not $selected) {
    if (-not [Environment]::Is64BitOperatingSystem) { throw 'Pilot requires a 64-bit Windows system.' }
    # ARM64 Windows must support x64 application emulation for the shipped native bridge.
    $key = 'jdk25.windows.x64'
    $destination = Join-Path $cache $manifest["$key.home"]
    $selected = Test-PilotJava (Join-Path $destination 'bin/java.exe')
    if (-not $selected) {
        if ($env:PILOT_OFFLINE -eq 'true') { throw 'Offline mode has no usable cached JDK 25.' }
        New-Item -ItemType Directory -Path $cache -Force | Out-Null
        $lock = $null
        $deadline = [DateTime]::UtcNow.AddMinutes(12)
        while (-not $lock) {
            try { $lock = [IO.File]::Open((Join-Path $cache 'java-bootstrap.lock'), 'OpenOrCreate', 'ReadWrite', 'None') }
            catch { if ([DateTime]::UtcNow -gt $deadline) { throw 'Another Java setup process did not finish.' }; Start-Sleep -Seconds 1 }
        }
        $stage = Join-Path $cache ('.jdk25-' + [Guid]::NewGuid().ToString('N'))
        try {
            $selected = Test-PilotJava (Join-Path $destination 'bin/java.exe')
            if (-not $selected) {
                New-Item -ItemType Directory -Path $stage | Out-Null
                Write-Host 'Pilot: downloading and verifying a private JDK 25. Global Java settings will not change.'
                [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
                $archive = Join-Path $stage 'jdk.zip'
                $request = @{Uri=$manifest["$key.url"];OutFile=$archive;UseBasicParsing=$true;TimeoutSec=600}
                if ($env:HTTPS_PROXY) { $request.Proxy = $env:HTTPS_PROXY }
                Invoke-WebRequest @request
                if ((Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash.ToLowerInvariant() -ne $manifest["$key.sha256"]) { throw 'JDK checksum failed. Nothing was installed.' }
                Expand-Archive -LiteralPath $archive -DestinationPath (Join-Path $stage 'unpack')
                $entries = @(Get-ChildItem -LiteralPath (Join-Path $stage 'unpack'))
                if ($entries.Count -ne 1 -or -not $entries[0].PSIsContainer) { throw 'JDK archive is invalid.' }
                if (Test-Path -LiteralPath $destination) { Move-Item -LiteralPath $destination -Destination ($destination + '.invalid-' + [Guid]::NewGuid().ToString('N')) }
                [IO.File]::WriteAllText((Join-Path $entries[0].FullName '.pilot-verified'), $manifest["$key.sha256"])
                Move-Item -LiteralPath $entries[0].FullName -Destination $destination
                $selected = Test-PilotJava (Join-Path $destination 'bin/java.exe')
                if (-not $selected) { throw 'Downloaded x64 Java cannot run. Check Windows version or x64 emulation support on ARM64.' }
            }
        } finally { if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }; $lock.Dispose() }
    }
}
$env:JAVA_HOME = $selected
$env:PATH = (Join-Path $selected 'bin') + ';' + $env:PATH
$java = Join-Path $selected 'bin/java.exe'
$source = Join-Path $root 'pilot-core/src/main/java/com/pilot/core/runtime/bootstrap/SourceEnvironment.java'
$mode = if ($env:PILOT_START_CHECK_ONLY -eq 'true') { 'prepare' } else { 'launch' }
& $java '--enable-native-access=ALL-UNNAMED' $source $mode $root @PilotArgs
exit $LASTEXITCODE

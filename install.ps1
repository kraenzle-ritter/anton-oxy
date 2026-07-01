# Installs the built plugin into the oXygen "plugins" folder on Windows.
# Copying into C:\Program Files usually needs an ELEVATED (Run as administrator)
# PowerShell. The add-on route (make-addon.ps1 + Help > Install new add-ons) does
# NOT need admin and survives oXygen updates — prefer it if you hit permission errors.
#
#   powershell -ExecutionPolicy Bypass -File .\install.ps1

$ErrorActionPreference = 'Stop'
Set-Location -Path $PSScriptRoot

function Find-Oxygen {
    if ($env:OXYGEN_DIR) { return $env:OXYGEN_DIR }
    foreach ($c in @('C:\Program Files\Oxygen XML Editor*','C:\Program Files\Oxygen XML Author*','C:\Program Files (x86)\Oxygen XML Editor*')) {
        $dir = Get-ChildItem $c -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
        if ($dir) { return $dir.FullName }
    }
    return $null
}

$version = '1.1.0'
$jar = "lib\anton-oxy-$version.jar"
if (-not (Test-Path $jar)) { Write-Error 'Plugin jar missing - run .\build.ps1 first.'; exit 1 }

$oxy = Find-Oxygen
if (-not $oxy -or -not (Test-Path (Join-Path $oxy 'plugins'))) {
    Write-Error "oXygen 'plugins' folder not found. Set `$env:OXYGEN_DIR."
    exit 1
}

$target = Join-Path $oxy 'plugins\anton-oxy'
Write-Host "Installing into: $target"
Remove-Item -Recurse -Force $target -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force (Join-Path $target 'lib') | Out-Null
Copy-Item 'plugin.xml' (Join-Path $target 'plugin.xml')
Copy-Item $jar (Join-Path $target 'lib\')

Write-Host 'Done. Restart oXygen, then use the "Anton" menu (Ctrl+Shift+A) or the "Anton @ref" toolbar button.'

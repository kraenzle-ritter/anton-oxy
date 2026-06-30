# Builds the anton-oxy plugin on Windows (PowerShell), no network access.
# Compiles to Java 8 bytecode against the installed oXygen jars.
#
# Usage (in the project folder):
#   powershell -ExecutionPolicy Bypass -File .\build.ps1
# Override the oXygen location if needed:
#   $env:OXYGEN_DIR = 'C:\Program Files\Oxygen XML Editor 27'; .\build.ps1

$ErrorActionPreference = 'Stop'
Set-Location -Path $PSScriptRoot

function Find-Oxygen {
    if ($env:OXYGEN_DIR) { return $env:OXYGEN_DIR }
    $candidates = @(
        'C:\Program Files\Oxygen XML Editor*',
        'C:\Program Files\Oxygen XML Author*',
        'C:\Program Files (x86)\Oxygen XML Editor*'
    )
    foreach ($c in $candidates) {
        $dir = Get-ChildItem $c -Directory -ErrorAction SilentlyContinue |
               Sort-Object Name -Descending | Select-Object -First 1
        if ($dir) { return $dir.FullName }
    }
    return $null
}

$oxy = Find-Oxygen
if (-not $oxy -or -not (Test-Path (Join-Path $oxy 'lib\oxygen.jar'))) {
    Write-Error "oxygen.jar not found. Set `$env:OXYGEN_DIR to your oXygen installation folder."
    exit 1
}

$version = '1.0.0'
$jarName = "anton-oxy-$version.jar"
$cp = (Get-ChildItem (Join-Path $oxy 'lib\*.jar') | ForEach-Object { $_.FullName }) -join ';'

Remove-Item -Recurse -Force 'build' -ErrorAction SilentlyContinue
Remove-Item -Force "lib\$jarName" -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force 'build\classes' | Out-Null
New-Item -ItemType Directory -Force 'lib' | Out-Null

Write-Host 'Compiling (Java 8 target) ...'
$sources = Get-ChildItem -Recurse 'src\main\java' -Filter *.java | ForEach-Object { $_.FullName }
$sources | Set-Content -Encoding ASCII 'build\sources.txt'
& javac --release 8 -encoding UTF-8 -classpath $cp -d 'build\classes' '@build\sources.txt'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "Packaging $jarName ..."
& jar --create --file "lib\$jarName" -C 'build\classes' '.'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "Built: lib\$jarName"
Write-Host "Install with:  .\install.ps1     (may require an elevated PowerShell)"
Write-Host "Or package an add-on with:  .\make-addon.ps1"

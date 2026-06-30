# Packages the plugin as an oXygen add-on on Windows (PowerShell):
#   dist\anton-oxy-<v>.zip   - the plugin archive
#   addon\updateSite.xml     - the add-on descriptor / update site
#
# Install in oXygen via:  Help > Install new add-ons...  ->  point to addon\updateSite.xml
# (This route needs no admin rights and survives oXygen updates.)
#
#   powershell -ExecutionPolicy Bypass -File .\make-addon.ps1

$ErrorActionPreference = 'Stop'
Set-Location -Path $PSScriptRoot

$version = '1.0.0'
$oxyMin  = '22.0'
$id      = 'ch.kr.anton.oxy'
$name    = 'anton-oxy'
$author  = 'K-R / UZH ZDE'
$jar     = "lib\anton-oxy-$version.jar"
$pkgDir  = "anton-oxy-$version"
$zip     = "$pkgDir.zip"

if (-not (Test-Path $jar)) { Write-Error 'Plugin jar missing - run .\build.ps1 first.'; exit 1 }

Remove-Item -Recurse -Force 'dist' -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "dist\$pkgDir\lib" | Out-Null
New-Item -ItemType Directory -Force 'addon' | Out-Null
Copy-Item 'plugin.xml' "dist\$pkgDir\plugin.xml"
Copy-Item $jar "dist\$pkgDir\lib\"

Compress-Archive -Path "dist\$pkgDir" -DestinationPath "dist\$zip" -Force
Write-Host "Created dist\$zip"

$xml = @"
<?xml version="1.0" encoding="UTF-8"?>
<xt:extensions xmlns:xt="http://www.oxygenxml.com/ns/extension">
    <xt:extension id="$id">
        <xt:location href="../dist/$zip"/>
        <xt:version>$version</xt:version>
        <xt:oxy_version>$oxyMin</xt:oxy_version>
        <xt:type>plugin</xt:type>
        <xt:author>$author</xt:author>
        <xt:name>$name</xt:name>
        <xt:description>
            <html xmlns="http://www.w3.org/1999/xhtml">
                <body>
                    <p>Searches actors and places live in Anton and writes the id
                    (e.g. {slug}-actors-123) into a configurable attribute (default
                    @ref) of the element under the caret.</p>
                </body>
            </html>
        </xt:description>
    </xt:extension>
</xt:extensions>
"@
Set-Content -Path 'addon\updateSite.xml' -Value $xml -Encoding UTF8
Write-Host 'Created addon\updateSite.xml'
Write-Host ''
Write-Host 'Install: oXygen -> Help -> Install new add-ons... -> path to addon\updateSite.xml'

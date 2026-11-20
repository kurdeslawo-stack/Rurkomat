param(
    [Parameter(Mandatory = $true)]
    [string]$ServerDir
)

$ErrorActionPreference = 'Stop'
$serverPath = (Resolve-Path -LiteralPath $ServerDir).Path
$libraries = (Get-ChildItem (Join-Path $serverPath 'libraries') -Recurse -Filter '*.jar').FullName
$paperApi = $libraries | Where-Object { $_ -match '[\\/]paper-api-26\.3\.[^\\/]+\.jar$' } | Sort-Object -Descending | Select-Object -First 1
$essentials = Get-ChildItem (Join-Path $serverPath 'plugins') -Filter 'EssentialsX-*.jar' |
    Where-Object { $_.Name -match '^EssentialsX-[0-9]' } | Select-Object -First 1
if (-not $paperApi -or -not $essentials) {
    throw 'Paper 26.3 and EssentialsX are required in the server directory.'
}

$build = Join-Path $PSScriptRoot 'build'
$classes = Join-Path $build 'classes'
[void](New-Item -ItemType Directory -Force -Path $classes)
$source = Join-Path $PSScriptRoot 'src/main/java/pl/rurkomat/Rurkomat.java'
$resources = Join-Path $PSScriptRoot 'src/main/resources'
$artifact = Join-Path $build 'Rurkomat-1.0.2.jar'
$classpath = (@($paperApi, $essentials.FullName) + ($libraries | Where-Object { $_ -notmatch '[\\/]paper-api-' })) -join [IO.Path]::PathSeparator

& javac --release 25 -encoding UTF-8 -cp $classpath -d $classes $source
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed.' }
& jar --create --file $artifact -C $classes pl/rurkomat -C $resources plugin.yml
if ($LASTEXITCODE -ne 0) { throw 'Packaging failed.' }
Write-Output $artifact

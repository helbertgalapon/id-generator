param(
  # Path to JavaFX jmods folder (e.g. C:\javafx\javafx-21\jmods)
  [Parameter(Mandatory = $false)]
  [string]$JavafxJmodsPath = $env:JAVAFX_JMODS,

  # Installer output folder
  [Parameter(Mandatory = $false)]
  [string]$Dest = "installer",

  # App name shown in installer/Start Menu
  [Parameter(Mandatory = $false)]
  [string]$AppName = "IdGenerator",

  # App version shown in installer metadata
  [Parameter(Mandatory = $false)]
  [string]$AppVersion = "1.0.0",

  # Show a console window (useful for diagnosing "closes immediately")
  [Parameter(Mandatory = $false)]
  [switch]$WinConsole
)

$ErrorActionPreference = "Stop"

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path

function Require-Command([string]$cmd) {
  $found = Get-Command $cmd -ErrorAction SilentlyContinue
  if (-not $found) {
    throw "Required command not found on PATH: $cmd"
  }
}

Require-Command "mvn"
Require-Command "jpackage"

if (-not $JavafxJmodsPath -or -not (Test-Path $JavafxJmodsPath)) {
  $default = "C:\javafx\javafx-21\jmods"
  if (Test-Path $default) {
    $JavafxJmodsPath = $default
  } else {
    throw @"
JavaFX jmods folder not found.

Set it via:
  - PowerShell parameter: -JavafxJmodsPath 'C:\path\to\javafx\jmods'
  - or environment variable: JAVAFX_JMODS

Example:
  .\build-installer.ps1 -JavafxJmodsPath 'C:\javafx\javafx-21\jmods'
"@
  }
}

# jlink needs every --add-modules artifact on --module-path. JavaFX jmods do not include JDK modules
# (e.g. jdk.httpserver); append the JDK that runs this script.
$javaExe = (Get-Command java -ErrorAction Stop).Source
$jdkHome = Split-Path (Split-Path $javaExe)
$jdkJmodsPath = Join-Path $jdkHome "jmods"
if (-not (Test-Path $jdkJmodsPath)) {
  throw "JDK jmods folder not found: $jdkJmodsPath (use a full JDK, not a JRE)."
}
$jpackageModulePath = "$JavafxJmodsPath;$jdkJmodsPath"

# Build project (creates target\classes and target\libs)
Write-Host "Building project with Maven..."
& mvn -q clean package

$projectRoot = $scriptRoot
$classesDir = Join-Path $projectRoot "target\classes"
$libsDir = Join-Path $projectRoot "target\libs"

if (-not (Test-Path $classesDir)) { throw "Missing: $classesDir (did Maven compile succeed?)" }
if (-not (Test-Path $libsDir)) { throw "Missing: $libsDir (did Maven copy dependencies?)" }

# jpackage uses the system temp directory by default (often outside the workspace).
# In restricted environments this can fail, so force a temp dir inside the project.
$localTemp = Join-Path $projectRoot ".jpackage-temp"
New-Item -ItemType Directory -Force -Path $localTemp | Out-Null
$env:TEMP = $localTemp
$env:TMP = $localTemp

# jpackage has two modes:
# - Modular mode (--module ...) which requires all third-party deps to be linkable modules.
# - Classpath mode (--main-jar/--main-class) which is more forgiving and works well with typical jars.
#
# We use classpath mode here so sqlite-jdbc/slf4j work reliably in the packaged runtime.
$targetDir = Join-Path $projectRoot "target"
$mainJar = Get-ChildItem -Path $targetDir -Filter "*.jar" -File |
  Where-Object { $_.FullName -notlike "*\target\libs\*" } |
  Sort-Object LastWriteTime |
  Select-Object -Last 1
if (-not $mainJar) { throw "Could not find main jar under: $targetDir" }

# The main JAR manifest (maven-jar-plugin) uses Class-Path: libs/*.jar — jpackage must
# receive the same layout as target/ after package, or the app exits immediately (missing
# pdfbox, gson, poi, zxing, slf4j-nop, javafx jars, etc.).
$sqliteJar = Get-ChildItem -Path $libsDir -Filter "sqlite-jdbc-*.jar" -File -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $sqliteJar) { throw "Missing sqlite-jdbc jar in: $libsDir (did Maven package succeed?)" }

$inputDir = Join-Path $projectRoot ".jpackage-input"
$inputLibs = Join-Path $inputDir "libs"
if (Test-Path $inputDir) {
  Remove-Item -LiteralPath $inputDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $inputLibs | Out-Null

Copy-Item -LiteralPath $mainJar.FullName -Destination (Join-Path $inputDir $mainJar.Name) -Force
Copy-Item -Path (Join-Path $libsDir "*") -Destination $inputLibs -Force

# Ensure destination exists
if (-not [System.IO.Path]::IsPathRooted($Dest)) {
  $Dest = Join-Path $projectRoot $Dest
}
New-Item -ItemType Directory -Force -Path $Dest | Out-Null

# Remove any previous installer so we can tell if a new one was generated
$oldInstallers = Get-ChildItem -Path $Dest -Filter "$AppName-*.exe" -ErrorAction SilentlyContinue
if ($oldInstallers) {
  foreach ($f in $oldInstallers) {
    try {
      Remove-Item -LiteralPath $f.FullName -Force -ErrorAction Stop
    } catch {
      Write-Warning "Could not remove old installer: $($f.FullName)"
      Write-Warning "Reason: $($_.Exception.Message)"
    }
  }
}

$args = @(
  "--type", "exe",
  "--name", $AppName,
  "--app-version", $AppVersion,
  "--dest", $Dest,
  "--resource-dir", (Join-Path $projectRoot "installer-resources"),
  "--input", $inputDir,
  "--main-jar", $mainJar.Name,
  "--main-class", "org.financial.App",

  # JavaFX + JDK modules (httpserver lives in JDK jmods, not in JavaFX jmods)
  "--module-path", $jpackageModulePath,
  # JDK modules: jlink only bundles what you list. Match module-info.java platform requires
  # (java.net.http = HttpClient for Supabase; jdk.httpserver = IdServer; java.prefs/java.desktop as required).
  "--add-modules", "javafx.controls,javafx.fxml,javafx.swing,java.sql,java.sql.rowset,java.desktop,java.net.http,java.prefs,jdk.httpserver",

  # A nice Windows install experience (no admin required for per-user install)
  "--win-per-user-install",
  "--win-menu",
  "--win-shortcut",
  "--win-dir-chooser"
)

if ($WinConsole) {
  $args += "--win-console"
}

Write-Host "Packaging installer with jpackage..."
Write-Host "Module path: $jpackageModulePath"
Write-Host "JavaFX jmods: $JavafxJmodsPath"
Write-Host "Output dir : $Dest"

& jpackage @args
if ($LASTEXITCODE -ne 0) {
  throw "jpackage failed with exit code $LASTEXITCODE"
}

$newInstaller = Get-ChildItem -Path $Dest -Filter "$AppName-*.exe" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime | Select-Object -Last 1
if (-not $newInstaller) {
  throw "jpackage did not produce an installer .exe in: $Dest"
}

Write-Host ""
Write-Host "Done. Installer: $($newInstaller.FullName)"

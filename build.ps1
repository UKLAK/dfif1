# Build script PowerShell cho JavaStudio (Core) va JavaStudio-Web (Extension)
$ErrorActionPreference = "Stop"

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "         JavaStudio Plugin Builder (Paper/Purpur/Spigot)  " -ForegroundColor Yellow
Write-Host "==========================================================" -ForegroundColor Cyan

# 1. Tim JDK (javac va jar)
$javacCmd = Get-Command javac -ErrorAction SilentlyContinue
$javac = if ($javacCmd) { $javacCmd.Source } else { $null }

$jarCmd = Get-Command jar -ErrorAction SilentlyContinue
$jar = if ($jarCmd) { $jarCmd.Source } else { $null }

if (-not $javac) {
    $prismJava = Get-ChildItem -Path "$env:APPDATA\PrismLauncher\java\*\bin\javac.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($prismJava) {
        $javac = $prismJava.FullName
        $jar = Join-Path (Split-Path $javac) "jar.exe"
    }
}

if (-not $javac -and $env:JAVA_HOME) {
    $javaHomeJavac = Join-Path $env:JAVA_HOME "bin\javac.exe"
    if (Test-Path $javaHomeJavac) {
        $javac = $javaHomeJavac
        $jar = Join-Path $env:JAVA_HOME "bin\jar.exe"
    }
}

if (-not $javac) {
    $progJava = Get-ChildItem -Path "C:\Program Files\Java\*\bin\javac.exe", "C:\Program Files\Eclipse Adoptium\*\bin\javac.exe", "C:\Program Files\Microsoft\*\bin\javac.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($progJava) {
        $javac = $progJava.FullName
        $jar = Join-Path (Split-Path $javac) "jar.exe"
    }
}

if (-not $javac -or -not (Test-Path $javac)) {
    Write-Error "Khong tim thay javac.exe! Vui long cai dat JDK 21+ hoac them javac vao PATH."
    exit 1
}

$versionOutput = (& $javac -version 2>&1)
Write-Host "[1/6] Su dung JDK Compiler: $javac ($versionOutput)" -ForegroundColor Green
Write-Host "      Muc tieu Bytecode: Java 21 LTS (Tuong thich toan bo server Java 21 -> 26+)" -ForegroundColor Cyan

# 2. Dam bao cac thu vien .libs ton tai
$libsDir = Join-Path $PSScriptRoot ".libs"
New-Item -ItemType Directory -Force -Path $libsDir | Out-Null

$dependencies = @(
    @{ Name = "paper-api.jar"; Url = "https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/1.20.6-R0.1-SNAPSHOT/paper-api-1.20.6-R0.1-20241030.191541-127.jar" },
    @{ Name = "adventure-api-4.17.0.jar"; Url = "https://repo1.maven.org/maven2/net/kyori/adventure-api/4.17.0/adventure-api-4.17.0.jar" },
    @{ Name = "adventure-key-4.17.0.jar"; Url = "https://repo1.maven.org/maven2/net/kyori/adventure-key/4.17.0/adventure-key-4.17.0.jar" },
    @{ Name = "bungeecord-chat-1.20-R0.2.jar"; Url = "https://repo.papermc.io/repository/maven-public/net/md-5/bungeecord-chat/1.20-R0.2/bungeecord-chat-1.20-R0.2.jar" },
    @{ Name = "annotations-24.1.0.jar"; Url = "https://repo1.maven.org/maven2/org/jetbrains/annotations/24.1.0/annotations-24.1.0.jar" },
    @{ Name = "examination-api-1.3.0.jar"; Url = "https://repo1.maven.org/maven2/net/kyori/examination-api/1.3.0/examination-api-1.3.0.jar" },
    @{ Name = "examination-string-1.3.0.jar"; Url = "https://repo1.maven.org/maven2/net/kyori/examination-string/1.3.0/examination-string-1.3.0.jar" },
    @{ Name = "guava-33.0.0-jre.jar"; Url = "https://repo1.maven.org/maven2/com/google/guava/guava/33.0.0-jre/guava-33.0.0-jre.jar" },
    @{ Name = "error_prone_annotations-2.27.0.jar"; Url = "https://repo1.maven.org/maven2/com/google/errorprone/error_prone_annotations/2.27.0/error_prone_annotations-2.27.0.jar" },
    @{ Name = "placeholderapi-2.11.6.jar"; Url = "https://repo.extendedclip.com/content/repositories/placeholderapi/me/clip/placeholderapi/2.11.6/placeholderapi-2.11.6.jar" },
    @{ Name = "netty-transport-4.1.100.Final.jar"; Url = "https://repo1.maven.org/maven2/io/netty/netty-transport/4.1.100.Final/netty-transport-4.1.100.Final.jar" },
    @{ Name = "netty-buffer-4.1.100.Final.jar"; Url = "https://repo1.maven.org/maven2/io/netty/netty-buffer/4.1.100.Final/netty-buffer-4.1.100.Final.jar" },
    @{ Name = "netty-common-4.1.100.Final.jar"; Url = "https://repo1.maven.org/maven2/io/netty/netty-common/4.1.100.Final/netty-common-4.1.100.Final.jar" },
    @{ Name = "brigadier-1.0.18.jar"; Url = "https://libraries.minecraft.net/com/mojang/brigadier/1.0.18/brigadier-1.0.18.jar" }
)

Write-Host "[2/6] Kiem tra thu vien phu thuoc (.libs)..." -ForegroundColor Yellow
foreach ($dep in $dependencies) {
    $dest = Join-Path $libsDir $dep.Name
    if (-not (Test-Path $dest)) {
        Write-Host "      Dang tai $($dep.Name)..." -ForegroundColor Gray
        Invoke-WebRequest -Uri $dep.Url -OutFile $dest -UseBasicParsing
    }
}
Write-Host "      Toan bo thu vien da san sang!" -ForegroundColor Green

# 3. Tao thu muc target
$targetDir = Join-Path $PSScriptRoot "target"
$classesDir = Join-Path $targetDir "classes"
$webClassesDir = Join-Path $targetDir "web-classes"

foreach ($dir in @($classesDir, $webClassesDir)) {
    if (Test-Path $dir) {
        Remove-Item -Recurse -Force $dir
    }
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
}

# 4. Bien dich Plugin Mac Dinh (JavaStudio Core)
$javaFiles = Get-ChildItem -Path (Join-Path $PSScriptRoot "src\main\java") -Filter "*.java" -Recurse | Select-Object -ExpandProperty FullName

Write-Host "[3/6] Dang bien dich $($javaFiles.Count) tap tin Java cho Plugin Mac Dinh (JavaStudio)..." -ForegroundColor Yellow

$sourcesListFile = Join-Path $targetDir "sources.txt"
$javaFiles | Out-File -FilePath $sourcesListFile -Encoding ascii

$classpath = (Get-ChildItem -Path $libsDir -Filter "*.jar" | Select-Object -ExpandProperty FullName) -join [System.IO.Path]::PathSeparator

$compileArgs = @(
    "--release", "21",
    "-cp", $classpath,
    "-d", $classesDir,
    "-encoding", "UTF-8",
    "-parameters",
    "-nowarn",
    "@$sourcesListFile"
)

$prevEA = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$javacOutput = & $javac @compileArgs 2>&1
$compileExit = $LASTEXITCODE
$ErrorActionPreference = $prevEA

if ($compileExit -ne 0) {
    $javacOutput | Write-Host -ForegroundColor Red
    Write-Error "Bien dich Plugin Mac Dinh (JavaStudio) that bai!"
    exit 1
}
Write-Host "      Bien dich JavaStudio Core thanh cong!" -ForegroundColor Green

# 5. Dong goi Plugin Mac Dinh (JavaStudio-1.0.0.jar)
Write-Host "[4/6] Dong goi Plugin Mac Dinh (JavaStudio-1.0.0.jar)..." -ForegroundColor Yellow
$resourcesDir = Join-Path $PSScriptRoot "src\main\resources"
Copy-Item -Path "$resourcesDir\*" -Destination $classesDir -Recurse -Force

$outputJar = Join-Path $targetDir "JavaStudio-1.0.0.jar"
if (Test-Path $outputJar) {
    Remove-Item -Force $outputJar
}
Push-Location $classesDir
& $jar "cf" $outputJar *
Pop-Location
Write-Host "      Da tao: $outputJar" -ForegroundColor Green

# 6. Bien dich va dong goi Plugin Mo Rong Web (JavaStudio-Web-1.0.0.jar)
$webJavaDir = Join-Path $PSScriptRoot "web-extension\src\main\java"
$webResourcesDir = Join-Path $PSScriptRoot "web-extension\src\main\resources"
$webOutputJar = Join-Path $targetDir "JavaStudio-Web-1.0.0.jar"

$webJavaFiles = Get-ChildItem -Path $webJavaDir -Filter "*.java" -Recurse | Select-Object -ExpandProperty FullName
Write-Host "[5/6] Dang bien dich $($webJavaFiles.Count) tap tin Java cho Plugin Mo Rong (JavaStudio-Web)..." -ForegroundColor Yellow

$webSourcesListFile = Join-Path $targetDir "web-sources.txt"
$webJavaFiles | Out-File -FilePath $webSourcesListFile -Encoding ascii

$webClasspath = $classpath + [System.IO.Path]::PathSeparator + $outputJar
$webCompileArgs = @(
    "--release", "21",
    "-cp", $webClasspath,
    "-d", $webClassesDir,
    "-encoding", "UTF-8",
    "-parameters",
    "-nowarn",
    "@$webSourcesListFile"
)

$prevEA = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$webJavacOutput = & $javac @webCompileArgs 2>&1
$webCompileExit = $LASTEXITCODE
$ErrorActionPreference = $prevEA

if ($webCompileExit -ne 0) {
    $webJavacOutput | Write-Host -ForegroundColor Red
    Write-Error "Bien dich Plugin Mo Rong (JavaStudio-Web) that bai!"
    exit 1
}
Write-Host "      Bien dich JavaStudio-Web thanh cong!" -ForegroundColor Green

Write-Host "[6/6] Dong goi Plugin Mo Rong (JavaStudio-Web-1.0.0.jar)..." -ForegroundColor Yellow
Copy-Item -Path "$webResourcesDir\*" -Destination $webClassesDir -Recurse -Force

if (Test-Path $webOutputJar) {
    Remove-Item -Force $webOutputJar
}
Push-Location $webClassesDir
& $jar "cf" $webOutputJar *
Pop-Location
Write-Host "      Da tao: $webOutputJar" -ForegroundColor Green

# Dong bo lop da bien dich vao thu muc bin/ cho IDE Language Server
$binDir = Join-Path $PSScriptRoot "bin"
if (Test-Path $binDir) {
    Get-ChildItem -Path (Join-Path $binDir "com\javastudio\anticheat") -Filter "AcWebDashboard*.class" -ErrorAction SilentlyContinue | Remove-Item -Force
    Copy-Item -Path "$classesDir\com" -Destination $binDir -Recurse -Force
    Copy-Item -Path "$webClassesDir\com" -Destination $binDir -Recurse -Force
}

Write-Host "==========================================================" -ForegroundColor Green
Write-Host "  HOAN TAT! Da tao 2 file plugin doc lap tai target\:" -ForegroundColor Green
Write-Host "  1. Plugin Mac Dinh : $outputJar" -ForegroundColor Cyan
Write-Host "  2. Plugin Mo Rong  : $webOutputJar (Yeu cau JavaStudio)" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Green

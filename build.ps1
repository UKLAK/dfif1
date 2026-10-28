# Build script PowerShell cho JavaStudio Plugin
$ErrorActionPreference = "Stop"

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "         JavaStudio Plugin Builder (Paper/Purpur/Spigot)  " -ForegroundColor Yellow
Write-Host "==========================================================" -ForegroundColor Cyan

# 1. Tìm JDK (javac và jar)
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
    Write-Error "Không tìm thấy javac.exe! Vui lòng cài đặt JDK 21+ hoặc thêm javac vào PATH."
    exit 1
}

$versionOutput = (& $javac -version 2>&1)
Write-Host "[1/5] Su dung JDK Compiler: $javac ($versionOutput)" -ForegroundColor Green
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
    @{ Name = "netty-common-4.1.100.Final.jar"; Url = "https://repo1.maven.org/maven2/io/netty/netty-common/4.1.100.Final/netty-common-4.1.100.Final.jar" },
    @{ Name = "brigadier-1.0.18.jar"; Url = "https://libraries.minecraft.net/com/mojang/brigadier/1.0.18/brigadier-1.0.18.jar" }
)

Write-Host "[2/5] Kiem tra thu vien phu thuoc (.libs)..." -ForegroundColor Yellow
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

if (Test-Path $classesDir) {
    Remove-Item -Recurse -Force $classesDir
}
New-Item -ItemType Directory -Force -Path $classesDir | Out-Null

# 4. Tim toan bo file Java de bien dich
$javaFiles = Get-ChildItem -Path (Join-Path $PSScriptRoot "src\main\java") -Filter "*.java" -Recurse | Select-Object -ExpandProperty FullName

Write-Host "[3/5] Dang bien dich $($javaFiles.Count) tap tin Java ma nguon..." -ForegroundColor Yellow

$sourcesListFile = Join-Path $targetDir "sources.txt"
$javaFiles | Out-File -FilePath $sourcesListFile -Encoding ascii

$classpath = (Get-ChildItem -Path $libsDir -Filter "*.jar" | Select-Object -ExpandProperty FullName) -join [System.IO.Path]::PathSeparator

$compileArgs = @(
    "--release", "21",
    "-cp", $classpath,
    "-d", $classesDir,
    "-encoding", "UTF-8",
    "-parameters",
    "-Xlint:all",
    "-Xlint:-processing",
    "@$sourcesListFile"
)

$prevEA = $ErrorActionPreference
$ErrorActionPreference = "SilentlyContinue"
& $javac @compileArgs 2> "$targetDir\lint_warnings.txt"
$compileExit = $LASTEXITCODE
$ErrorActionPreference = $prevEA

if ($compileExit -ne 0) {
    Get-Content "$targetDir\lint_warnings.txt" | Write-Host -ForegroundColor Red
    Write-Error "Bien dich ma nguon Java that bai!"
    exit 1
}
if (Test-Path "$targetDir\lint_warnings.txt") {
    $warnings = Get-Content "$targetDir\lint_warnings.txt"
    if ($warnings) {
        Write-Host "--- Cac canh bao cua trinh bien dich (javac) ---" -ForegroundColor Yellow
        $warnings | Write-Host -ForegroundColor Yellow
    }
}
Write-Host "      Bien dich thanh cong!" -ForegroundColor Green

# 5. Sao chep resources (plugin.yml, config.yml)
Write-Host "[4/5] Sao chep file cau hinh va plugin.yml..." -ForegroundColor Yellow
$resourcesDir = Join-Path $PSScriptRoot "src\main\resources"
Copy-Item -Path "$resourcesDir\*" -Destination $classesDir -Recurse -Force

# Dong bo lop da bien dich vao thu muc bin/ cho IDE Language Server
$binDir = Join-Path $PSScriptRoot "bin"
if (Test-Path $binDir) {
    Copy-Item -Path "$classesDir\*" -Destination $binDir -Recurse -Force
}

# 6. Dong goi file JAR
$outputJar = Join-Path $targetDir "JavaStudio-1.0.0.jar"
Write-Host "[5/5] Dang dong goi JAR tai $outputJar..." -ForegroundColor Yellow

Push-Location $classesDir
& $jar "cvf" $outputJar *
Pop-Location

Write-Host "==========================================================" -ForegroundColor Green
Write-Host "  THANH CONG! File plugin da duoc tao tai:" -ForegroundColor Green
Write-Host "  $outputJar" -ForegroundColor Cyan
Write-Host "  Copy file nay vao thu muc 'plugins/' cua server Minecraft!" -ForegroundColor Yellow
Write-Host "==========================================================" -ForegroundColor Green

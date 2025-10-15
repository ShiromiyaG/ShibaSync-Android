# 🔧 Script to compile Oboe native library

Write-Host "================================" -ForegroundColor Cyan
Write-Host "🔨 COMPILING OBOE LIBRARY" -ForegroundColor Cyan
Write-Host "================================" -ForegroundColor Cyan
Write-Host ""

$projectPath = "c:\Users\castr\AndroidStudioProjects\syncmusic - websocket jitter (refinado)"

# Check if the project exists
if (-not (Test-Path $projectPath)) {
    Write-Host "❌ Project not found at: $projectPath" -ForegroundColor Red
    exit 1
}

Set-Location $projectPath

# Step 1: Clean previous build
Write-Host "🧹 Cleaning previous build..." -ForegroundColor Yellow
.\gradlew clean

if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ Failed to clean project" -ForegroundColor Red
    exit 1
}

Write-Host "✅ Project cleaned" -ForegroundColor Green
Write-Host ""

# Step 2: Compile project with CMake
Write-Host "🔨 Compiling project (including native library)..." -ForegroundColor Yellow
.\gradlew assembleDebug

if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ Compilation failed" -ForegroundColor Red
    Write-Host ""
    Write-Host "💡 TIPS:" -ForegroundColor Yellow
    Write-Host "1. Check if Android SDK is installed" -ForegroundColor White
    Write-Host "2. Check if CMake is installed (via Android Studio SDK Manager)" -ForegroundColor White
    Write-Host "3. Check if NDK is installed (via Android Studio SDK Manager)" -ForegroundColor White
    exit 1
}

Write-Host "✅ Compilation completed" -ForegroundColor Green
Write-Host ""

# Step 3: Verify if the library was generated
Write-Host "🔍 Checking generated libraries..." -ForegroundColor Yellow

# ✅ Search in multiple possible locations
$possiblePaths = @(
    "app\build\intermediates\cxx\Debug\*\obj\arm64-v8a\liboboe-audio.so",
    "app\build\intermediates\cmake\debug\obj\arm64-v8a\liboboe-audio.so",
    "app\build\intermediates\merged_native_libs\debug\*\out\lib\arm64-v8a\liboboe-audio.so"
)

$libFound = $false
foreach ($pattern in $possiblePaths) {
    $foundFiles = Get-ChildItem -Path $pattern -ErrorAction SilentlyContinue
    if ($foundFiles) {
        foreach ($file in $foundFiles) {
            $fileSize = $file.Length
            Write-Host "✅ liboboe-audio.so found!" -ForegroundColor Green
            Write-Host "   Size: $([math]::Round($fileSize / 1KB, 2)) KB" -ForegroundColor Cyan
            Write-Host "   Location: $($file.FullName.Replace($projectPath + '\', ''))" -ForegroundColor Cyan
            $libFound = $true
            break
        }
        if ($libFound) { break }
    }
}

if (-not $libFound) {
    Write-Host "⚠️ Library not found in expected locations" -ForegroundColor Yellow
    Write-Host "   Searching in entire build directory..." -ForegroundColor Yellow
    $allLibs = Get-ChildItem -Path "app\build" -Recurse -Filter "liboboe-audio.so" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($allLibs) {
        $fileSize = $allLibs.Length
        Write-Host "✅ Found in alternative location:" -ForegroundColor Green
        Write-Host "   Size: $([math]::Round($fileSize / 1KB, 2)) KB" -ForegroundColor Cyan
        Write-Host "   Location: $($allLibs.FullName.Replace($projectPath + '\', ''))" -ForegroundColor Cyan
        $libFound = $true
    }
    else {
        Write-Host "❌ liboboe-audio.so was NOT generated!" -ForegroundColor Red
        Write-Host ""
        Write-Host "💡 Possible causes:" -ForegroundColor Yellow
        Write-Host "1. CMakeLists.txt with syntax error" -ForegroundColor White
        Write-Host "2. OboeAudioPlayer.cpp with compilation error" -ForegroundColor White
        Write-Host "3. Oboe library not found by CMake" -ForegroundColor White
        Write-Host "4. Check build logs above for CMake errors" -ForegroundColor White
        exit 1
    }
}

Write-Host ""

# Step 4: Install on device (if connected)
Write-Host "📱 Installing APK on device..." -ForegroundColor Yellow
.\gradlew installDebug

if ($LASTEXITCODE -eq 0) {
    Write-Host "✅ APK installed successfully!" -ForegroundColor Green
}
else {
    Write-Host "⚠️ Could not install (device not connected or USB debugging disabled)" -ForegroundColor Yellow
    Write-Host "📦 APK generated at: app\build\outputs\apk\debug\app-debug.apk" -ForegroundColor Cyan
}

Write-Host ""
Write-Host "================================" -ForegroundColor Green
Write-Host "✅ BUILD COMPLETED SUCCESSFULLY" -ForegroundColor Green
Write-Host "================================" -ForegroundColor Green
Write-Host ""
Write-Host "🎯 NEXT STEPS:" -ForegroundColor Cyan
Write-Host "1. If APK was not installed automatically, install manually" -ForegroundColor White
Write-Host "2. Open YouTube/Spotify and PLAY MUSIC" -ForegroundColor Yellow
Write-Host "3. Run Shiba Sync app and start capture" -ForegroundColor White
Write-Host "4. Check logs with: adb logcat | Select-String 'OboeNative'" -ForegroundColor White
Write-Host ""
Write-Host "📋 CHANGES IN THIS BUILD:" -ForegroundColor Cyan
Write-Host "  ✅ Buffer increased: 500ms → 700ms (fewer cuts)" -ForegroundColor Green
Write-Host "  ✅ Improved diagnostics in AudioCaptureService" -ForegroundColor Green
Write-Host "  ✅ Oboe buffer optimized: 67% → 90%" -ForegroundColor Green

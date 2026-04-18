@echo off
setlocal enabledelayedexpansion

set "ADB=C:\Android\Sdk\platform-tools\adb.exe"
set "DEST_PKG=com.edge.ai.gallery"
set "TEMP_DIR=%TEMP%\edge_ai_models"

echo ============================================
echo  Edge AI - Copy Models via PC (fallback)
echo ============================================
echo.

:: ── Check device ─────────────────────────────
"%ADB%" devices | findstr /R "device$" >nul 2>&1
if errorlevel 1 (
  echo [ERROR] No Android device detected.
  pause & exit /b 1
)

:: ── Find source package ───────────────────────
echo [1/4] Looking for Google AI Edge Gallery...
set "SRC_PKG="
for /f "tokens=2 delims=:" %%A in ('"%ADB%" shell pm list packages ^| findstr "edge.gallery"') do (
  set "CANDIDATE=%%A"
  set "CANDIDATE=!CANDIDATE: =!"
  set "CANDIDATE=!CANDIDATE:~0,-1!"
  if not "!CANDIDATE!"=="%DEST_PKG%" set "SRC_PKG=!CANDIDATE!"
)

if "!SRC_PKG!"=="" (
  echo [ERROR] Google AI Edge Gallery not found.
  pause & exit /b 1
)
echo       Found: !SRC_PKG!
echo.

:: ── Pull to PC ────────────────────────────────
echo [2/4] Pulling models to PC temp folder...
echo       %TEMP_DIR%
if exist "%TEMP_DIR%" rd /s /q "%TEMP_DIR%"
mkdir "%TEMP_DIR%"

"%ADB%" pull "/sdcard/Android/data/!SRC_PKG!/files/" "%TEMP_DIR%"
if errorlevel 1 (
  echo.
  echo [WARN] adb pull had errors. Trying run-as fallback...
  "%ADB%" shell run-as !SRC_PKG! tar -c files | "%ADB%" exec-out tar -x -C "%TEMP_DIR%"
)
echo.

:: ── Push to our app ───────────────────────────
echo [3/4] Pushing models to Edge AI...
"%ADB%" shell mkdir -p "/sdcard/Android/data/%DEST_PKG%/files/"
"%ADB%" push "%TEMP_DIR%\files\." "/sdcard/Android/data/%DEST_PKG%/files/"
if errorlevel 1 (
  echo [ERROR] Push failed.
  pause & exit /b 1
)
echo.

:: ── Cleanup ───────────────────────────────────
echo [4/4] Cleaning up temp files on PC...
rd /s /q "%TEMP_DIR%"
echo.

echo ============================================
echo  Done! Open Edge AI - models show as
echo  Installed in the Models screen.
echo ============================================
echo.
pause

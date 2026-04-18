@echo off
setlocal enabledelayedexpansion

set "ADB=C:\Android\Sdk\platform-tools\adb.exe"
set "SRC_PKG=com.google.ai.edge.gallery"
set "DEST_PKG=com.edge.ai.gallery"
set "TEMP_DIR=C:\edge_ai_models_temp"
set "PHONE_STAGING=/sdcard/Download/edge_ai_staging"

echo ============================================
echo  Edge AI - Copy Models from Google App
echo ============================================
echo.

:: ── Check device ─────────────────────────────
"%ADB%" devices | findstr /R "device$" >nul 2>&1
if errorlevel 1 (
  echo [ERROR] No Android device detected.
  pause & exit /b 1
)

:: ── Check if already pulled ───────────────────
if exist "%TEMP_DIR%\files" (
  echo [INFO] Found previously pulled files at %TEMP_DIR%
  echo        Skipping pull step.
  goto :push
)

:: ── Pull model files to PC ───────────────────
echo [1/3] Pulling model files from Google app to PC...
echo       This may take a while for large models.
echo.
if exist "%TEMP_DIR%" rd /s /q "%TEMP_DIR%"
mkdir "%TEMP_DIR%"
"%ADB%" pull "/sdcard/Android/data/%SRC_PKG%/files/" "%TEMP_DIR%"
if errorlevel 1 (
  echo [ERROR] Pull failed.
  pause & exit /b 1
)

:push
echo.
echo [2/3] Pushing to phone staging area ^(/sdcard/Download^)...
echo       ^(Android/data is write-protected via ADB, using Download as bridge^)
echo.

:: Clean up old staging if exists
"%ADB%" shell rm -rf "%PHONE_STAGING%"
"%ADB%" shell mkdir -p "%PHONE_STAGING%"

:: Push to Download folder (always ADB-writable)
"%ADB%" push "%TEMP_DIR%\files\." "%PHONE_STAGING%/"
if errorlevel 1 (
  echo [ERROR] Push to staging failed.
  pause & exit /b 1
)

echo.
echo [3/3] Moving from staging into Edge AI app folder...
"%ADB%" shell "mkdir -p /sdcard/Android/data/%DEST_PKG%/files/ && cp -rn %PHONE_STAGING%/. /sdcard/Android/data/%DEST_PKG%/files/ && rm -rf %PHONE_STAGING%"
if errorlevel 1 (
  echo [WARN] Move step had errors - some files may not have copied.
  echo        Check the Models screen in Edge AI.
) else (
  echo.
  echo Cleaning up PC temp files...
  rd /s /q "%TEMP_DIR%"
)

echo.
echo ============================================
echo  Done! Open Edge AI - tap the hamburger
echo  menu, go to Models - the downloaded
echo  models should show as Installed.
echo ============================================
echo.
pause

@echo off
rem zen.cmd - run the Zen build tool without typing "python zen.py".
rem Detects a bundled toolchain next to zen.py (or one folder up) and
rem sets ZEN_JAVA / ZEN_GRADLE / ZEN_SDK only if you have not set them.
rem Usage:  zen <command> [args...]   e.g.  zen build app.zip --name "My App"
setlocal
set "ZEN_DIR=%~dp0"
set "ZEN_PY=%ZEN_DIR%zen.py"
set "TOOLCHAIN="

if exist "%ZEN_DIR%toolchain" set "TOOLCHAIN=%ZEN_DIR%toolchain"
if not defined TOOLCHAIN if exist "%ZEN_DIR%..\toolchain" set "TOOLCHAIN=%ZEN_DIR%..\toolchain"

if defined TOOLCHAIN (
    if not defined ZEN_JAVA (
        for /d %%d in ("%TOOLCHAIN%\jdk*") do set "ZEN_JAVA=%%~fd"
    )
    if not defined ZEN_GRADLE (
        if exist "%TOOLCHAIN%\gradle-8.9" (
            set "ZEN_GRADLE=%TOOLCHAIN%\gradle-8.9"
        ) else (
            for /d %%d in ("%TOOLCHAIN%\gradle*") do set "ZEN_GRADLE=%%~fd"
        )
    )
    if not defined ZEN_SDK (
        if exist "%TOOLCHAIN%\sdk" set "ZEN_SDK=%TOOLCHAIN%\sdk"
    )
)

where py >nul 2>nul
if %ERRORLEVEL%==0 (
    py "%ZEN_PY%" %*
    exit /b %ERRORLEVEL%
)
where python >nul 2>nul
if %ERRORLEVEL%==0 (
    python "%ZEN_PY%" %*
    exit /b %ERRORLEVEL%
)
where python3 >nul 2>nul
if %ERRORLEVEL%==0 (
    python3 "%ZEN_PY%" %*
    exit /b %ERRORLEVEL%
)
echo [zen] Python not found. Install it from https://www.python.org/downloads/ and tick "Add to PATH".
exit /b 1
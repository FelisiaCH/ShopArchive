@echo off
rem setlocal keeps DRIVE/JAVA/EXITCODE out of the caller. A real ERRORLEVEL variable in the environment would shadow
rem the dynamic %ERRORLEVEL% below and hide java's exit code, so clear it.
setlocal
set "ERRORLEVEL="
rem ShopArchive server launcher. chcp: UTF-8 console for Lao/Thai.
chcp 65001 >nul
rem cmd can't make a UNC path the current directory ("CMD does not support UNC paths as current
rem directories") - the cd /d below would then silently leave us wherever we started (often System32)
rem and the relative tmp\jvm and crash-reports paths below would point there, not under the root. %~d0 needs no cwd,
rem so refuse first and keep cmd's own error message out of the way.
set "DRIVE=%~d0"
if "%DRIVE:~0,2%"=="\\" (
  echo [ERROR] ShopArchive cannot run from a network path: %~dp0
  echo Copy this folder to a local disk and run start.bat from there.
  pause
  exit /b 1
)

rem cd first: java.exe mangles non-ANSI chars in argv, but cwd survives - so every path below stays
rem relative/ASCII and the root defaults to this script's folder.
cd /d "%~dp0"

set "JAVA=java"
if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java"

rem The one memory knob: JVM heap can't live in a YAML config, since that's read after the JVM starts.
if not defined SHOPARCHIVE_MEMORY set "SHOPARCHIVE_MEMORY=1G"

"%JAVA%" -Xms%SHOPARCHIVE_MEMORY% -Xmx%SHOPARCHIVE_MEMORY% ^
  -Dshoparchive.start-script=true ^
  -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 ^
  -Djava.io.tmpdir=tmp\jvm -XX:-UsePerfData -XX:ErrorFile=crash-reports\hs_err_pid%%p.log ^
  -jar shoparchive-server.jar %*

rem Java's exit code, kept across pause so a refused start still fails for whoever called this script.
set "EXITCODE=%ERRORLEVEL%"

rem Double-click users must see the error before the window closes.
pause
exit /b %EXITCODE%

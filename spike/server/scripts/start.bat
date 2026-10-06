@echo off
rem Spike server launcher (T3). chcp: UTF-8 console for Lao/Thai.
chcp 65001 >nul
rem cd first: java.exe loses non-ANSI chars (Lao) in command-line args, but cwd keeps them.
rem So every path below is relative/ASCII and the root defaults to this folder.
cd /d "%~dp0"
if not exist tmp mkdir tmp
if not exist crash-reports mkdir crash-reports
set "JAVA=java"
if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java"
"%JAVA%" -Xmx512m -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Djava.io.tmpdir=tmp -Djna.tmpdir=tmp -XX:-UsePerfData -XX:ErrorFile=crash-reports/hs_err_%%p.log -jar spike-server.jar %*
pause

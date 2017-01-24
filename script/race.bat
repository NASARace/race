@REM script to run config encryption under Windows

@echo off

set "SCRIPT_DIR=%~dp0..\target\universal\stage\bin"

"%SCRIPT_DIR%\race.bat" %*

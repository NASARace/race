@REM script to run config decryption under Windows

@echo off

set "SCRIPT_DIR=%~dp0..\race-tools\target\universal\stage\bin"

"%SCRIPT_DIR%\race-tools.bat" -main gov.nasa.race.tool.CryptConfig --decrypt %*


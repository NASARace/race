@REM script to run config encryption under Windows

@echo off

set "SCRIPT_DIR=%~dp0\\..\race-tools\universal\stage\bin"

"%SCRIPT_DIR\race-tools -main gov.nasa.race.tool.CryptConfig --encrypt %%1"

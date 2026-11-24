@echo off
rem Bitacora Digital - doble clic para iniciar. Cerrar esta ventana detiene el programa.
cd /d "%~dp0"
title Bitacora Digital (no cierre esta ventana mientras la usa)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0iniciar.ps1"
if errorlevel 1 pause

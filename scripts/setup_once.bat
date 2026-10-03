@echo off
REM Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
REM This software is released under the BSD 3-Clause License.
REM See the LICENSE.txt file in the project root for full license information.
REM =============================================================
REM PointAndIdentify - setup_once.bat
REM Version 1.0
REM Purpose : Double-click launcher for setup_once.ps1 (one-time repository
REM           setup: signing key, secrets, GitHub Pages, first workflow runs).
REM           Run after the first update_github.bat.
REM =============================================================
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup_once.ps1"
endlocal

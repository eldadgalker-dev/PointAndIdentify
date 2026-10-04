@echo off
REM Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
REM This software is released under the BSD 3-Clause License.
REM See the LICENSE.txt file in the project root for full license information.
REM =============================================================
REM PointAndIdentify - update_github_blackhp17.bat
REM Version 1.0
REM Purpose : Same as update_github.bat, but pushes with the GitHub account
REM           BlackHP17. That account must have write access to
REM           eldadgalker-dev/PointAndIdentify (collaborator, invitation accepted).
REM           Drag a project zip onto this file, or double-click for a dialog.
REM           Requires update_github.ps1 (Version 2.1 or later) in the same folder.
REM =============================================================
setlocal
set "SCRIPT=%~dp0update_github.ps1"
set "ACCOUNT=BlackHP17"
if "%~1"=="" (
    powershell -NoProfile -STA -ExecutionPolicy Bypass -File "%SCRIPT%" -GitHubUser %ACCOUNT%
) else (
    powershell -NoProfile -STA -ExecutionPolicy Bypass -File "%SCRIPT%" -ZipPath "%~1" -GitHubUser %ACCOUNT%
)
endlocal

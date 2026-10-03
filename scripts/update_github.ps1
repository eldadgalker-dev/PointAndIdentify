# Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
# This software is released under the BSD 3-Clause License.
# See the LICENSE.txt file in the project root for full license information.
# =============================================================
# PointAndIdentify - update_github.ps1
# Version 2.0
# Purpose : One-step publisher. Takes a project zip (as delivered), mirrors
#           its contents into a local clone of the GitHub repository
#           (including hidden folders such as .github and deletions),
#           commits and pushes. Paths that are generated or added in the
#           repository itself (terrain data, Gradle wrapper) are preserved
#           once they exist in the repository; until then they come from the zip.
#           GitHub then works automatically: the release workflow builds a
#           signed APK and publishes a Release when app code changes; the data
#           workflow rebuilds targets and terrain when the data tools change.
# Runs on : Windows 10/11, PowerShell 5.1+, Git for Windows installed.
#           First push opens a browser window to sign in (Git Credential
#           Manager); later pushes are silent.
# Usage   : double-click update_github.bat, or drag a zip onto it, or
#           powershell -File update_github.ps1 -ZipPath C:\path\x.zip [-Message "text"]
# Encoding: UTF-8 without BOM, CRLF or LF both accepted by PowerShell
# =============================================================

param(
    [string]$ZipPath = "",
    [string]$Message = ""
)

# =============================================================
# Parameters
# =============================================================
$RepoUrl        = "https://github.com/eldadgalker-dev/PointAndIdentify.git"   # remote repository
$Branch         = "main"                                              # default branch
$LocalDir       = Join-Path $env:USERPROFILE "PointAndIdentify-repo"  # local clone location
$GitHubUser     = "eldadgalker-dev"   # GitHub account used for push; pinned per repository so another
                                     # account remembered by Windows (e.g. for other projects) is not used
$GitUserName    = "Eldad Galker"                                      # commit author
$GitUserEmail   = "eldad@galker.com"
$ProjectMarker  = "settings.gradle.kts"     # file that identifies the project root inside the zip
$ExcludeDirs    = @(".git", ".gradle", "build")   # never copied or deleted by the mirror (top level)
# Repository-owned paths, relative to the root: never overwritten or deleted by the mirror.
#   data              : terrain tiles, targets, manifest produced by tools/*.py
#   tools\anchors.json: produced by tools/build_targets.py
#   assets targets    : copy of data/targets.json bundled in the APK
#   Gradle wrapper    : generated once in the repository (not shipped in the zip)
$PreserveDirs   = @("data")             # preserved only if already present in the repository
$PreserveFiles  = @("tools\anchors.json", "app\src\main\assets\targets.json",
                    "gradlew", "gradlew.bat", "gradle\wrapper\gradle-wrapper.jar")

# =============================================================
# Validation and helpers
# =============================================================
# "Continue": PowerShell 5.1 turns redirected stderr of native commands (git
# writes progress there) into terminating errors under "Stop". Exit codes are
# checked explicitly instead.
$ErrorActionPreference = "Continue"

function Fail($msg) {
    Write-Host ""
    Write-Host "ERROR: $msg" -ForegroundColor Red
    Write-Host ""
    Read-Host "Press Enter to close"
    exit 1
}

function Step($msg) { Write-Host ""; Write-Host "==> $msg" -ForegroundColor Cyan }

function Run-Git {
    # Run git with arguments, stream output, fail on non-zero exit.
    param([string[]]$GitArgs)
    & git @GitArgs
    if ($LASTEXITCODE -ne 0) { Fail "git $($GitArgs -join ' ') failed (exit $LASTEXITCODE)" }
}

function Push-WithHint {
    # git push with a diagnosis of the usual exit-128 causes (authentication / permission).
    param([string[]]$PushArgs)
    & git @PushArgs
    if ($LASTEXITCODE -eq 0) { return }
    $code = $LASTEXITCODE
    Write-Host ""
    Write-Host "Push failed. Read the 'fatal:' / 'remote:' line above:" -ForegroundColor Yellow
    Write-Host "  'Permission ... denied to <user>' : another GitHub account was used instead of $GitHubUser." -ForegroundColor Yellow
    Write-Host "      Fix: in the browser sign out of that account and sign in as $GitHubUser," -ForegroundColor Yellow
    Write-Host "           then run this tool again and approve the sign-in window." -ForegroundColor Yellow
    Write-Host "  'Authentication failed' / 'could not read Username' : the sign-in window was closed or blocked." -ForegroundColor Yellow
    Write-Host "      Fix: run this tool again and complete the browser sign-in." -ForegroundColor Yellow
    Write-Host "  'Repository not found' : the signed-in account cannot see $RepoUrl." -ForegroundColor Yellow
    Write-Host "Your commit is kept locally; the next run pushes it." -ForegroundColor Yellow
    Fail "git $($PushArgs -join ' ') failed (exit $code)"
}

# -- Git present? --
$gitCmd = Get-Command git -ErrorAction SilentlyContinue
if (-not $gitCmd) {
    Start-Process "https://git-scm.com/download/win"
    Fail "Git for Windows is not installed. The download page has been opened. Install with default options, then run this tool again."
}

# -- Zip path: argument, or file dialog --
# A path that reaches the script truncated (cmd.exe splits unquoted paths at spaces
# and at characters such as & ( ) when a file is dragged onto the .bat) falls back
# to the dialog instead of failing; the dialog path never passes through cmd.exe.
function Select-Zip([string]$startDir) {
    Add-Type -AssemblyName System.Windows.Forms
    $dlg = New-Object System.Windows.Forms.OpenFileDialog
    $dlg.Title = "Select the PointAndIdentify project zip"
    $dlg.Filter = "Zip files (*.zip)|*.zip"
    $dlg.InitialDirectory = if ($startDir -and (Test-Path -LiteralPath $startDir)) { $startDir }
                            else { [Environment]::GetFolderPath("MyDocuments") }
    if ($dlg.ShowDialog() -ne [System.Windows.Forms.DialogResult]::OK) { exit 0 }
    return $dlg.FileName
}
if ($ZipPath -and -not (Test-Path -LiteralPath $ZipPath -PathType Leaf)) {
    Write-Host "Zip path not found: $ZipPath" -ForegroundColor Yellow
    Write-Host "The path was probably cut at a space or a special character (& ( ) ). Select the file in the dialog." -ForegroundColor Yellow
    $start = if (Test-Path -LiteralPath $ZipPath -PathType Container) { $ZipPath } else { Split-Path -Parent $ZipPath }
    $ZipPath = Select-Zip $start
} elseif (-not $ZipPath) {
    $ZipPath = Select-Zip ""
}
if (-not (Test-Path -LiteralPath $ZipPath -PathType Leaf)) { Fail "Zip not found: $ZipPath" }

# =============================================================
# 1. Local clone: create or refresh (handles an empty remote on first run)
# =============================================================
Step "Preparing local repository at $LocalDir"
$isRepo = $false
if (Test-Path -LiteralPath $LocalDir) {
    Push-Location $LocalDir
    & git rev-parse --is-inside-work-tree *> $null
    $isRepo = ($LASTEXITCODE -eq 0)
    Pop-Location
}

# Does the remote branch exist? An empty GitHub repository has no branch yet.
$remoteHeads = (& git ls-remote --heads $RepoUrl $Branch)
if ($LASTEXITCODE -ne 0) { Fail "Cannot reach $RepoUrl. Check the URL and your network." }
$remoteHasBranch = [bool]$remoteHeads

if (-not $isRepo) {
    if ((Test-Path -LiteralPath $LocalDir) -and (Get-ChildItem -LiteralPath $LocalDir -Force | Select-Object -First 1)) {
        Fail "The folder $LocalDir exists but is not a Git repository (probably left over from an interrupted first run). Delete that folder and run the tool again."
    }
    New-Item -ItemType Directory -Force -Path $LocalDir | Out-Null
    if ($remoteHasBranch) {
        Write-Host "Cloning $RepoUrl (a browser sign-in window may open)..."
        Run-Git @("clone", "--branch", $Branch, $RepoUrl, $LocalDir)
    } else {
        Write-Host "Remote repository is empty: initialising a new local repository."
        Run-Git @("-C", $LocalDir, "init", "-b", $Branch)
        Run-Git @("-C", $LocalDir, "remote", "add", "origin", $RepoUrl)
    }
}
Set-Location $LocalDir
$origin = ((& git remote get-url origin) | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or -not $origin) { Fail "Could not read the remote URL of $LocalDir. Delete the folder and run again." }
if ($origin -ne $RepoUrl) { Fail "Local folder points to a different repository ($origin). Delete $LocalDir or fix RepoUrl." }

Run-Git @("config", "user.name", $GitUserName)
Run-Git @("config", "user.email", $GitUserEmail)
# Local (repository-only) setting: Git Credential Manager selects the credential of this
# account; global settings and other projects are not affected.
Run-Git @("config", "credential.username", $GitHubUser)
if ($remoteHasBranch) {
    Run-Git @("fetch", "--tags", "origin", $Branch)
    Run-Git @("checkout", $Branch)
    Run-Git @("reset", "--hard", "origin/$Branch")   # local clone is a mirror; remote state wins
}

# =============================================================
# 2. Extract the zip and locate the project root
# =============================================================
Step "Extracting $ZipPath"
$tmp = Join-Path $env:TEMP ("pointandidentify_" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $tmp | Out-Null
Expand-Archive -LiteralPath $ZipPath -DestinationPath $tmp -Force

$src = $tmp
if (-not (Test-Path (Join-Path $src $ProjectMarker))) {
    $candidates = @(Get-ChildItem -Path $tmp -Directory | Where-Object { Test-Path (Join-Path $_.FullName $ProjectMarker) })
    if ($candidates.Count -ne 1) { Fail "Could not find $ProjectMarker in the zip (expected at root or in exactly one sub-folder)." }
    $src = $candidates[0].FullName
}
Write-Host "Project root: $src"

# =============================================================
# 3. Mirror the extracted project into the clone
# =============================================================
Step "Mirroring files into the repository"
# Full paths on both sides restrict each exclusion to that exact location; a bare
# name such as "data" would also exclude every nested folder with that name.
# A preserved path is excluded only when it already exists in the repository; otherwise
# (first run, or a repository created with only a README) it is taken from the zip.
$xd = @()
foreach ($d in $ExcludeDirs) { $xd += (Join-Path $LocalDir $d); $xd += (Join-Path $src $d) }
$preserved = @()
foreach ($d in $PreserveDirs) {
    if (Test-Path -LiteralPath (Join-Path $LocalDir $d)) {
        $xd += (Join-Path $LocalDir $d); $xd += (Join-Path $src $d); $preserved += $d
    }
}
$xf = @()
foreach ($f in $PreserveFiles) {
    if (Test-Path -LiteralPath (Join-Path $LocalDir $f)) {
        $xf += (Join-Path $LocalDir $f); $xf += (Join-Path $src $f); $preserved += $f
    }
}
$firstRun = -not $remoteHasBranch
# /XF is added only when there is something to exclude: an empty /XF is an invalid robocopy argument.
$rcArgs = @($src, $LocalDir, "/MIR", "/NFL", "/NDL", "/NJH", "/NJS", "/NP", "/R:1", "/W:1", "/XD") + $xd
if ($xf.Count -gt 0) { $rcArgs += @("/XF") + $xf }
& robocopy @rcArgs | Out-Null
if ($LASTEXITCODE -ge 8) { Fail "robocopy failed with exit code $LASTEXITCODE" }
Remove-Item -Recurse -Force $tmp
if ($preserved.Count -gt 0) {
    Write-Host "Preserved (repository-owned): $($preserved -join ', ')" -ForegroundColor DarkGray
} else {
    Write-Host "Nothing to preserve yet: data and assets taken from the zip." -ForegroundColor DarkGray
}

# =============================================================
# 4. Commit and push
# =============================================================
if (-not $Message) {
    $stamp = Get-Date -Format "yyyy-MM-dd HH:mm"
    $Message = "PointAndIdentify update $stamp"
}

Step "Committing"
Run-Git @("add", "-A")
$status = (& git status --porcelain)
if (-not $status) {
    Write-Host "Nothing changed: the repository already matches the zip." -ForegroundColor Yellow
} else {
    Write-Host ($status | Out-String)
    Run-Git @("commit", "-m", $Message)
}

# Push whenever the remote lacks commits we have: covers a new commit and also a commit
# left unpushed by an earlier failed run (remote branch still missing, or local ahead).
$needPush = $false
& git rev-parse --verify --quiet HEAD *> $null
$hasCommit = ($LASTEXITCODE -eq 0)
if ($hasCommit) {
    if ($firstRun) { $needPush = $true }
    else {
        $ahead = ((& git rev-list --count "origin/$Branch..HEAD") | Out-String).Trim()
        $needPush = ($ahead -and [int]$ahead -gt 0)
    }
}
if ($needPush) {
    Step "Pushing to $Branch (a browser sign-in window may open the first time)"
    if ($firstRun) { Push-WithHint @("push", "-u", "origin", $Branch) } else { Push-WithHint @("push", "origin", $Branch) }
}

$web = $RepoUrl -replace "\.git$", ""
Write-Host ""
Write-Host "Done. Source updated: $web" -ForegroundColor Green
if ($needPush) {
    Write-Host "Automatic on GitHub (progress: $web/actions):"
    Write-Host "  - app code changed  -> signed APK + Release; phones get it with the Update button"
    Write-Host "  - data tools changed -> targets and terrain rebuilt and committed"
    Write-Host "First time only: run setup_once.bat (signing key, secrets, help page)."
    Start-Process "$web/actions"
}
Read-Host "Press Enter to close"

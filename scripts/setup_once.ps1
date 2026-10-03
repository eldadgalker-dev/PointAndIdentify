# Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
# This software is released under the BSD 3-Clause License.
# See the LICENSE.txt file in the project root for full license information.
# =============================================================
# PointAndIdentify - setup_once.ps1
# Version 1.0
# Purpose : One-time setup of the GitHub repository so that everything after
#           it runs automatically:
#             1. signing key (created once, kept on this PC, reused on re-run)
#             2. the four POINT_* signing secrets in the repository
#             3. GitHub Pages from main /docs (help page)
#             4. first run of the data workflow and of the release workflow
#           Safe to run again: existing key, secrets and Pages are reused/updated.
# Runs on : Windows 10/11, PowerShell 5.1+, GitHub CLI (gh), a JDK keytool
#           (Android Studio includes one).
# Order   : run update_github.bat first (workflows must exist on GitHub), then this.
# Encoding: UTF-8 without BOM, CRLF
# =============================================================

# =============================================================
# Parameters
# =============================================================
$RepoSlug    = "eldadgalker-dev/PointAndIdentify"   # owner/repository
$GitHubUser  = "eldadgalker-dev"                    # account that owns the repository
$SigningDir  = Join-Path $env:USERPROFILE "PointAndIdentify-signing"   # key + passwords, never committed
$KeyAlias    = "point"                              # key alias inside the keystore; stored as secret
$KeyDname    = "CN=Eldad Galker, O=Galker, C=IL"     # certificate subject
$KeyValidity = 10000                                # days (~27 years); updates require the same key
$PasswordLen = 24                                   # random password length (letters and digits)
$KeytoolCandidates = @(
    "$env:JAVA_HOME\bin\keytool.exe",
    "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe",
    "C:\Program Files\Android\Android Studio\jre\bin\keytool.exe"
)

# =============================================================
# Helpers
# =============================================================
$ErrorActionPreference = "Continue"

function Fail($msg) {
    Write-Host ""
    Write-Host "ERROR: $msg" -ForegroundColor Red
    Write-Host ""
    Read-Host "Press Enter to close"
    exit 1
}

function Step($msg) { Write-Host ""; Write-Host "==> $msg" -ForegroundColor Cyan }

function New-Password([int]$len) {
    $chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".ToCharArray()
    $bytes = New-Object byte[] $len
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    return -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })
}

function Gh-Login {
    $login = ((& gh api user --jq .login 2>$null) | Out-String).Trim()
    return $login
}

# =============================================================
# 1. GitHub CLI signed in as the repository owner
# =============================================================
Step "Checking GitHub CLI"
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    Start-Process "https://cli.github.com/"
    Fail "GitHub CLI (gh) is not installed. The download page has been opened. Install it, then run this tool again."
}
$login = Gh-Login
if ($login -ne $GitHubUser) {
    if ($login) {
        Write-Host "gh is signed in as '$login'; switching to '$GitHubUser'..."
        & gh auth switch --hostname github.com --user $GitHubUser 2>$null
        $login = Gh-Login
    }
    if ($login -ne $GitHubUser) {
        Write-Host "Sign in as '$GitHubUser' in the browser window (if the browser shows another account, switch account first)."
        & gh auth login --hostname github.com --git-protocol https --web
        $login = Gh-Login
    }
}
if ($login -ne $GitHubUser) { Fail "gh is signed in as '$login', expected '$GitHubUser'." }
Write-Host "Signed in as $login"

& gh repo view $RepoSlug --json name *> $null
if ($LASTEXITCODE -ne 0) { Fail "Repository $RepoSlug not reachable. Run update_github.bat first." }
& gh workflow view release.yml --repo $RepoSlug *> $null
if ($LASTEXITCODE -ne 0) { Fail "Workflow release.yml not found on GitHub. Publish the latest zip with update_github.bat first." }

# =============================================================
# 2. Signing key (created once, reused afterwards)
# =============================================================
Step "Signing key in $SigningDir"
New-Item -ItemType Directory -Force -Path $SigningDir | Out-Null
$keystore = Join-Path $SigningDir "release.jks"
$pwFile = Join-Path $SigningDir "passwords.txt"

if ((Test-Path -LiteralPath $keystore) -and (Test-Path -LiteralPath $pwFile)) {
    $storePass = ((Get-Content -LiteralPath $pwFile | Where-Object { $_ -like "password=*" }) -replace "^password=", "").Trim()
    if (-not $storePass) { Fail "$pwFile has no 'password=' line. Restore it from your backup." }
    Write-Host "Existing key found: reusing it."
} else {
    if (Test-Path -LiteralPath $keystore) { Fail "$keystore exists but $pwFile is missing. Restore passwords.txt from your backup." }
    $keytool = (Get-Command keytool -ErrorAction SilentlyContinue).Source
    if (-not $keytool) { $keytool = $KeytoolCandidates | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -First 1 }
    if (-not $keytool) { Fail "keytool not found. Install Android Studio (or a JDK 17) and run this tool again." }
    $storePass = New-Password $PasswordLen
    # PKCS12 keystores use one password for store and key.
    & $keytool -genkeypair -noprompt -storetype PKCS12 -keystore $keystore -alias $KeyAlias `
        -keyalg RSA -keysize 2048 -validity $KeyValidity -dname $KeyDname `
        -storepass $storePass -keypass $storePass
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $keystore)) { Fail "keytool failed to create the key." }
    @("alias=$KeyAlias", "password=$storePass", "created=$(Get-Date -Format s)") | Set-Content -LiteralPath $pwFile -Encoding ASCII
    Write-Host "New key created." -ForegroundColor Green
}
Write-Host "BACK UP the folder $SigningDir (e.g. to a USB drive). Without it no further updates can be published." -ForegroundColor Yellow

# =============================================================
# 3. Repository secrets
# =============================================================
Step "Setting repository secrets"
$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($keystore))
$secrets = [ordered]@{
    "POINT_KEYSTORE_B64"      = $b64
    "POINT_KEYSTORE_PASSWORD" = $storePass
    "POINT_KEY_ALIAS"         = $KeyAlias
    "POINT_KEY_PASSWORD"      = $storePass
}
foreach ($name in $secrets.Keys) {
    # --body, not a pipe: PowerShell 5.1 appends a newline when piping to native programs,
    # which would become part of the secret and break signing.
    & gh secret set $name --repo $RepoSlug --body $secrets[$name]
    if ($LASTEXITCODE -ne 0) { Fail "Could not set secret $name." }
    Write-Host "  $name set"
}

# =============================================================
# 4. GitHub Pages from main /docs
# =============================================================
Step "Enabling GitHub Pages (main /docs)"
& gh api "repos/$RepoSlug/pages" *> $null
if ($LASTEXITCODE -eq 0) {
    & gh api -X PUT "repos/$RepoSlug/pages" -f "source[branch]=main" -f "source[path]=/docs" *> $null
} else {
    & gh api -X POST "repos/$RepoSlug/pages" -f "source[branch]=main" -f "source[path]=/docs" *> $null
}
if ($LASTEXITCODE -ne 0) {
    Write-Host "Pages could not be configured automatically. Set it once by hand: Settings > Pages > main, /docs." -ForegroundColor Yellow
} else {
    Write-Host "Pages enabled."
}

# =============================================================
# 5. First runs: data (targets + terrain), then release (signed APK)
# =============================================================
Step "Starting workflows"
& gh workflow run data.yml --repo $RepoSlug
if ($LASTEXITCODE -ne 0) { Write-Host "Could not start data.yml; start it from the Actions tab." -ForegroundColor Yellow }
& gh workflow run release.yml --repo $RepoSlug
if ($LASTEXITCODE -ne 0) { Write-Host "Could not start release.yml; start it from the Actions tab." -ForegroundColor Yellow }

$owner = $RepoSlug.Split("/")[0]
$repo = $RepoSlug.Split("/")[1]
Write-Host ""
Write-Host "Setup complete." -ForegroundColor Green
Write-Host "  Progress : https://github.com/$RepoSlug/actions"
Write-Host "  Install  : https://github.com/$RepoSlug/releases/latest/download/PointAndIdentify.apk  (after the release run finishes)"
Write-Host "  Help page: https://$owner.github.io/$repo/  (a few minutes after Pages is enabled)"
Start-Process "https://github.com/$RepoSlug/actions"
Read-Host "Press Enter to close"

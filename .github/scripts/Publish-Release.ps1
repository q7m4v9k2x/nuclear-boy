#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version,
    [string]$NotesFile,
    [string]$SdkRoot,
    [switch]$Publish
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$repo = 'q7m4v9k2x/nuclear-boy'
$tag = "v$Version"
if (!$SdkRoot) { $SdkRoot = $env:ANDROID_HOME }
if (!$SdkRoot) { $SdkRoot = $env:ANDROID_SDK_ROOT }
if (!$SdkRoot) {
    $sdkLine = Get-Content (Join-Path $repoRoot 'local.properties') | Where-Object { $_ -match '^sdk.dir=' } | Select-Object -First 1
    $SdkRoot = ($sdkLine -replace '^sdk.dir=', '').Replace('\:', ':').Replace('\\', '\')
}
$buildTools = Get-ChildItem (Join-Path $SdkRoot 'build-tools') -Directory |
    Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (!$buildTools) { throw 'Android SDK build-tools required.' }
$aapt = Join-Path $buildTools.FullName 'aapt.exe'
$apksigner = Join-Path $buildTools.FullName 'apksigner.bat'
$artifacts = foreach ($variant in @('debug', 'release')) {
    $apkPath = Join-Path $repoRoot "app/build/outputs/apk/$variant/app-$variant.apk"
    if (!(Test-Path -LiteralPath $apkPath)) { throw "Missing $variant APK; build both variants before publishing." }
    $badging = & $aapt dump badging $apkPath
    if ($LASTEXITCODE -ne 0) { throw "Cannot inspect $variant APK." }
    $packageLine = $badging | Where-Object { $_ -match '^package:' } | Select-Object -First 1
    if ($packageLine -notmatch "name='([^']+)' versionCode='(\d+)' versionName='([^']+)'") { throw 'Missing APK package metadata.' }
    $expectedPackage = if ($variant -eq 'debug') { 'com.nuclearboy.app.debug' } else { 'com.nuclearboy.app' }
    if ($Matches[1] -ne $expectedPackage -or $Matches[3] -ne $Version) { throw "Wrong package/version in $variant APK." }
    $versionCode = [long]$Matches[2]
    $certificates = & $apksigner verify --print-certs $apkPath
    if ($LASTEXITCODE -ne 0) { throw "Invalid signature in $variant APK." }
    $certLine = $certificates | Where-Object { $_ -match '^Signer #1 certificate SHA-256 digest: ' } | Select-Object -First 1
    if (!$certLine) { throw 'Missing signer certificate.' }
    [pscustomobject]@{
        Name = "nuclear-boy-$Version-$variant.apk"
        Path = $apkPath
        VersionCode = $versionCode
        Certificate = $certLine.Split(':')[-1].Trim()
        Size = (Get-Item -LiteralPath $apkPath).Length
        Digest = 'sha256:' + (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}
if ($artifacts[0].VersionCode -ne $artifacts[1].VersionCode) { throw 'APK versionCode mismatch.' }
if ($artifacts[0].Certificate -ne $artifacts[1].Certificate) { throw 'APK signing certificate mismatch.' }
# Public certificate fingerprint of the existing 1.1.75 distribution. A newly
# generated debug keystore cannot upgrade installs signed with this key.
if ($artifacts[0].Certificate -ne 'baa0b4ba6186810181db40b57fce2d21efb425b2a7d89c6b1b1f92d871a3deab') {
    throw 'APK signer changed; restore the existing signing key before publishing.'
}
$artifacts | Select-Object Name,VersionCode,Size,Digest | Format-List
if (!$Publish) { return }
if (!$NotesFile -or !(Test-Path -LiteralPath $NotesFile)) { throw 'Provide a release notes file with -NotesFile.' }
if (git -C $repoRoot status --porcelain) { throw 'Commit changes before publishing.' }
$commit = (git -C $repoRoot rev-parse HEAD).Trim()
$remote = git -C $repoRoot ls-remote "https://github.com/$repo.git" refs/heads/main
if ($LASTEXITCODE -ne 0 -or ($remote -split '\s+')[0] -ne $commit) { throw 'Push HEAD to the owned repository main before publishing.' }
$tagRefs = @(git -C $repoRoot ls-remote "https://github.com/$repo.git" "refs/tags/$tag" "refs/tags/$tag^{}")
if ($LASTEXITCODE -ne 0) { throw 'Cannot verify remote tag.' }
if ($tagRefs.Count -gt 0) {
    $tagCommit = $tagRefs | Where-Object { $_ -match '\^\{\}$' } | Select-Object -First 1
    if (!$tagCommit) { $tagCommit = $tagRefs[0] }
    if (($tagCommit -split '\s+')[0] -ne $commit) { throw 'Existing tag points to another commit.' }
}

# Credentials remain in memory; never put them in command arguments or release notes.
$token = $env:GITHUB_TOKEN
if (!$token) { $token = (Get-Content (Join-Path $env:USERPROFILE '.github_cli/config.json') -Raw | ConvertFrom-Json).token }
if (!$token) { throw 'GitHub authentication is unavailable.' }
$headers = @{ Authorization = "Bearer $token"; Accept = 'application/vnd.github+json'; 'X-GitHub-Api-Version' = '2022-11-28' }
$api = "https://api.github.com/repos/$repo"
$release = $null
try { $release = Invoke-RestMethod "$api/releases/tags/$tag" -Headers $headers }
catch { if ([int]$_.Exception.Response.StatusCode -ne 404) { throw } }
# GitHub's by-tag endpoint may omit drafts. Authenticated release listings
# include them, so an interrupted upload can resume without creating duplicates.
if (!$release) {
    $page = 1
    do {
        $releases = Invoke-RestMethod "$api/releases?per_page=100&page=$page" -Headers $headers
        $matching = @($releases | Where-Object { $_.tag_name -eq $tag })
        if ($matching.Count -gt 1) { throw 'Multiple releases use this tag; inspect them before retrying.' }
        if ($matching.Count -eq 1) { $release = $matching[0]; break }
        $page++
    } while ($releases.Count -eq 100)
}
if ($release -and !$release.draft) { throw 'Release is already public; published attachments are not modified by this script.' }
if ($release -and $release.target_commitish -ne $commit) { throw 'Existing draft targets a different commit.' }
if ($release -and ($release.assets | Where-Object { $_.name -like '*.apk' -and $_.name -notin $artifacts.Name })) {
    throw 'Draft contains unexpected APK attachments; inspect them before publishing.'
}
if (!$release) {
    $body = @{ tag_name = $tag; name = "Nuclear Boy $tag"; target_commitish = $commit; draft = $true; prerelease = $false; body = (Get-Content -LiteralPath $NotesFile -Raw) } | ConvertTo-Json
    $release = Invoke-RestMethod "$api/releases" -Method Post -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $body
}
foreach ($artifact in $artifacts) {
    $existing = @($release.assets | Where-Object { $_.name -eq $artifact.Name })
    if ($existing.Count -gt 0) {
        if ($existing.Count -ne 1 -or $existing[0].size -ne $artifact.Size -or $existing[0].digest -ne $artifact.Digest) {
            throw "Draft attachment differs: $($artifact.Name). Inspect the draft before retrying."
        }
        continue
    }
    $uploadUrl = ($release.upload_url -split '\{')[0] + '?name=' + [uri]::EscapeDataString($artifact.Name)
    $null = Invoke-RestMethod $uploadUrl -Method Post -Headers $headers -ContentType 'application/vnd.android.package-archive' -InFile $artifact.Path -TimeoutSec 300
}
$verified = Invoke-RestMethod "$api/releases/$($release.id)" -Headers $headers
if ($verified.assets | Where-Object { $_.name -like '*.apk' -and $_.name -notin $artifacts.Name }) {
    throw 'Remote draft contains unexpected APK attachments.'
}
foreach ($artifact in $artifacts) {
    $asset = @($verified.assets | Where-Object { $_.name -eq $artifact.Name })
    if ($asset.Count -ne 1 -or $asset[0].state -ne 'uploaded' -or $asset[0].size -ne $artifact.Size -or $asset[0].digest -ne $artifact.Digest) {
        throw "Remote attachment verification failed: $($artifact.Name). Release remains a draft."
    }
}
$published = Invoke-RestMethod "$api/releases/$($release.id)" -Method Patch -Headers $headers -ContentType 'application/json' -Body '{"draft":false,"prerelease":false,"make_latest":"true"}'
Write-Output $published.html_url
$published.assets | ForEach-Object { Write-Output $_.browser_download_url }

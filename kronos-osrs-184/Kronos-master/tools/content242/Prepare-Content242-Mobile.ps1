param(
 [string]$WorkspaceRoot=(Resolve-Path "$PSScriptRoot/../../../../..").Path,
 [string]$MobileRoot='C:\K242-Mobile-QA'
)
$ErrorActionPreference='Stop'
$original=Join-Path $WorkspaceRoot 'kronos-mobile'
$receiptRoot=Join-Path $WorkspaceRoot 'local-runtime/content242-mobile-release'
$destination=[IO.Path]::GetFullPath($MobileRoot)
if(Test-Path -LiteralPath $destination){throw 'Destination already exists; refusing to overwrite an existing checkout'}
if($destination -eq [IO.Path]::GetFullPath($original)){throw 'Original mobile checkout cannot be the destination'}
$before=@(& git -C $original status --porcelain=v1);if($LASTEXITCODE){throw 'Cannot inspect original mobile status'}
$head=(& git -C $original rev-parse HEAD).Trim()
$branch=(& git -C $original branch --show-current).Trim()
$paths=@(& git -C $original ls-files --cached --others --exclude-standard) | Sort-Object -Unique
if($LASTEXITCODE){throw 'Cannot enumerate current mobile source'}
$paths=@($paths | Where-Object { $_ -notmatch '(^|/)(build|\.gradle|\.cxx)/|(^|/)(hs_err_|replay_)|\.(mdmp|jks|keystore|p12|pfx|pem|p8)$|(^|/)local\.properties$|src/main/assets/jvm/' })
$manifest=@();foreach($relative in $paths){$file=Join-Path $original $relative;if(Test-Path -LiteralPath $file -PathType Leaf){$manifest+=@{path=$relative;sha256=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()}}}
& git -c core.longpaths=true -C $original worktree add -b codex/content242-mobile-isolated $destination $head
if($LASTEXITCODE){throw 'Isolated mobile worktree creation failed'}
foreach($entry in $manifest){$target=Join-Path $destination $entry.path;New-Item -ItemType Directory -Force (Split-Path $target -Parent)|Out-Null;Copy-Item -LiteralPath (Join-Path $original $entry.path) -Destination $target -Force;if((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.sha256){throw "Snapshot mismatch: $($entry.path)"}}
foreach($relative in @(& git -C $original ls-files --deleted)){ $target=Join-Path $destination $relative;if(Test-Path -LiteralPath $target){Remove-Item -LiteralPath $target} }
$after=@(& git -C $original status --porcelain=v1);if(($before -join "`n") -ne ($after -join "`n")){throw 'Original mobile working status changed during snapshot'}
New-Item -ItemType Directory -Force $receiptRoot|Out-Null
@{createdUtc=[DateTime]::UtcNow.ToString('o');source=$original;sourceHead=$head;sourceBranch=$branch;sourceStatus=$before;worktree=$destination;branch='codex/content242-mobile-isolated';files=$manifest;excluded='Generated builds/runtime, crash dumps and private keys';originalStatusUnchanged=$true}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Join-Path $receiptRoot 'source-snapshot.json') -Encoding utf8
Write-Output "Created isolated current-source mobile snapshot: $($manifest.Count) files; original working status unchanged."

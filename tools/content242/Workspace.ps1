$workspaceCandidate = $PSScriptRoot
while ($workspaceCandidate -and !(Test-Path -LiteralPath (Join-Path $workspaceCandidate 'tools/jdk8/jdk8u512-b09/bin/java.exe'))) {
    $workspaceCandidate = Split-Path $workspaceCandidate -Parent
}
if (!$workspaceCandidate) { throw 'Could not locate the workspace development tools' }
$root = $workspaceCandidate
$project = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent

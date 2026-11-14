param([string]$JavaHome=(Get-ChildItem "$PSScriptRoot/tools/jdk8" -Directory|Select-Object -First 1).FullName)
$ErrorActionPreference='Stop'
$runtime=Join-Path $PSScriptRoot 'local-runtime'
New-Item -ItemType Directory -Force $runtime|Out-Null
# Restrict the parent before Java creates key material. Do not use recursive
# icacls inheritance removal: it can erase existing child-file permissions.
if((Get-Item -LiteralPath $runtime -Force).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Refusing a reparse-point private runtime directory'}
$acl=New-Object Security.AccessControl.DirectorySecurity
$acl.SetAccessRuleProtection($true,$false)
foreach($sid in (@([Security.Principal.WindowsIdentity]::GetCurrent().User.Value,'S-1-5-18','S-1-5-32-544')|Select-Object -Unique)){
    $identity=New-Object Security.Principal.SecurityIdentifier($sid)
    $rule=New-Object Security.AccessControl.FileSystemAccessRule($identity,[Security.AccessControl.FileSystemRights]::FullControl,[Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit',[Security.AccessControl.PropagationFlags]::None,[Security.AccessControl.AccessControlType]::Allow)
    $acl.AddAccessRule($rule)
}
Set-Acl -LiteralPath $runtime -AclObject $acl
$build=Join-Path $runtime 'rsa-helper'
New-Item -ItemType Directory -Force $build|Out-Null
$privateFile=Join-Path $runtime 'rsa-private-exponent.txt'
$publicFile=Join-Path $runtime 'rsa-public-modulus.txt'
if((Test-Path -LiteralPath $privateFile) -or (Test-Path -LiteralPath $publicFile)){throw 'Existing key files will not be replaced'}
$project=Join-Path $PSScriptRoot 'kronos-osrs-184/Kronos-master'
$server=Join-Path $project 'kronos-server/src/main/java/io/ruin/network/LoginDecoder.java'
$serverText=[IO.File]::ReadAllText($server)
$match=[regex]::Match($serverText,'BigInteger MODULUS = new BigInteger\("([0-9]+)"\)')
if(!$match.Success){throw 'Server public modulus declaration changed'}
$old=$match.Groups[1].Value
$client=Join-Path $project 'runelite/runelite-client/src/main/java/net/runelite/standalone/Client.java'
$clientText=[IO.File]::ReadAllText($client)
if(!$clientText.Contains('"'+$old+'"')){throw 'Client/server public modulus mismatch; inspect before generating'}
& "$JavaHome/bin/javac.exe" -d $build "$PSScriptRoot/tools/GenerateLocalRsa.java"
if($LASTEXITCODE){throw 'RSA helper compile failed'}
& "$JavaHome/bin/java.exe" -cp $build GenerateLocalRsa $privateFile $publicFile
if($LASTEXITCODE){throw 'RSA generation failed'}
$public=([IO.File]::ReadAllText($publicFile)).Trim()
[IO.File]::WriteAllText($server,$serverText.Replace('"'+$old+'"','"'+$public+'"'))
[IO.File]::WriteAllText($client,$clientText.Replace('"'+$old+'"','"'+$public+'"'))
'Server/client public keys updated in this clone. Rebuild both clients and server. Keep local-runtime/rsa-private-exponent.txt private.'

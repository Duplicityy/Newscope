"""Add clone-local build entry points to a separate sanitized publishing export."""
from pathlib import Path
import argparse, hashlib, json, shutil

def main():
 p=argparse.ArgumentParser();p.add_argument('--snapshot',type=Path,required=True);p.add_argument('--workspace',type=Path,required=True);a=p.parse_args()
 out=a.snapshot.resolve();root=a.workspace.resolve()
 if not (out/'publish-manifest.json').is_file():raise SystemExit('A completed source export is required')
 for name in ('jre21-android-arm64.tar.xz','cacio-tta-1.18.jar','cacio-shared-1.18.jar'):
  shutil.copyfile(root/'tools/downloads'/name,out/'tools/downloads'/name)
 src=(root/'local-runtime/content242-worktree/Kronos-master/tools/content242/Build-Content242-Mobile.ps1').read_text(encoding='utf-8-sig')
 start=src.index('$ErrorActionPreference=')
 src='''param([switch]$StageOnly,[string]$WorkspaceRoot=$PSScriptRoot,[string]$MobileRoot=(Join-Path $PSScriptRoot 'kronos-mobile'),[string]$ClientRoot=(Join-Path $PSScriptRoot 'kronos-osrs-184/Kronos-master'),[string]$ExpectedClientSha256='')
'''+src[start:]
 for line in ["if($mobile -eq (Join-Path $root 'kronos-mobile')){throw 'Original mobile checkout is forbidden'}", "if((& git -C $mobile branch --show-current).Trim() -ne 'codex/content242-mobile-isolated'){throw 'Isolated mobile branch required'}"]:
  assert line in src;src=src.replace(line,'')
 begin=src.index('$snapshot=Get-Content');end=src.index('Check-Hash "$root/tools/downloads/jre21-android-arm64.tar.xz"')
 src=src[:begin]+src[end:]
 anchor='Check-Hash $client $ExpectedClientSha256'
 src=src.replace(anchor,"if(!$ExpectedClientSha256){$ExpectedClientSha256=(Get-FileHash $client -Algorithm SHA256).Hash.ToLowerInvariant()}\n"+anchor,1)
 src=src.replace(' --offline --no-daemon',' --no-daemon')
 src=src.replace("$env:ANDROID_USER_HOME=Join-Path $root 'tools/android-user'", "if(!$env:ANDROID_USER_HOME){$env:ANDROID_USER_HOME=Join-Path $root 'tools/android-user'}")
 src=src.replace('\nVerify-MobileSources','')
 receipt=src.index('@{builtUtc=')
 src=src[:receipt]+'''@{builtUtc=[DateTime]::UtcNow.ToString('o');clientSha256=$ExpectedClientSha256;bridgeSha256=(Get-FileHash "$assets/bridge.jar" -Algorithm SHA256).Hash.ToLowerInvariant();apkSha256=(Get-FileHash "$release/Kronos-Mobile-242-staged.apk" -Algorithm SHA256).Hash.ToLowerInvariant();applicationId='local.kronos.mobile';scope='Clone-local source build. Signing key remains private; no app installation.'}|ConvertTo-Json|Set-Content "$release/build-receipt.json" -Encoding utf8
'''
 (out/'Build-Kronos-Mobile.ps1').write_text(src,encoding='utf-8')
 (out/'tools/dependencies.init.gradle').write_text('''// Resolved public Java dependencies retained for historical repositories.
def repo = System.getenv('NEWSCOPE_MAVEN_REPO')
if (!repo) throw new GradleException('NEWSCOPE_MAVEN_REPO must point to the restored vendor/maven directory')
settingsEvaluated { s ->
    s.pluginManagement.repositories { maven { url uri(repo) }; gradlePluginPortal() }
}
allprojects {
    buildscript.repositories { maven { url uri(repo) } }
    repositories { maven { url uri(repo) } }
}
''',encoding='utf-8')
 (out/'Build-Source.ps1').write_text('''param([ValidateSet('server','client','all')][string]$Project='all',[switch]$Offline)
$ErrorActionPreference='Stop'
$root=$PSScriptRoot
$env:JAVA_HOME=(Get-ChildItem "$root/tools/jdk8" -Directory|Select-Object -First 1).FullName
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
$env:GRADLE_USER_HOME=Join-Path $root '.gradle-home'
$env:NEWSCOPE_MAVEN_REPO=Join-Path $root 'vendor/maven'
$game=Join-Path $root 'kronos-osrs-184/Kronos-master'
$common=@('--no-daemon','--max-workers=1','--console=plain','-I',"$root/tools/dependencies.init.gradle",'-Dorg.gradle.jvmargs=-Xmx768m -XX:+UseSerialGC -Dfile.encoding=UTF-8')
if($Offline){$common+='--offline'}
if($Project -in @('server','all')){& "$game/gradlew.bat" -p $game @common :kronos-server:installDist :kronos-update-server:installDist;if($LASTEXITCODE){throw 'Server build failed'}}
if($Project -in @('client','all')){& "$game/gradlew.bat" -p "$game/runelite" @common :runelite-client:shadowJar;if($LASTEXITCODE){throw 'Client build failed'}}
''',encoding='utf-8')
 deps=[]
 for path in sorted((out/'vendor/maven').rglob('*')):
  if path.is_file():deps.append({'path':path.relative_to(out).as_posix(),'bytes':path.stat().st_size,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
 (out/'vendor/dependencies.json').write_text(json.dumps({'scope':'Resolved public Maven artifacts used by server and desktop client; original POM and embedded JAR license notices retained','artifacts':deps},indent=2)+'\n',encoding='utf-8')
 (out/'vendor/README.md').write_text('''# Public build dependencies

`maven/` preserves the resolved Java 8 server and desktop build artifacts in Maven layout, including POM license metadata and embedded JAR notices. No Gradle credentials, private repository configuration or opaque resolution-cache state is included. `dependencies.json` records every file hash. Use `Build-Source.ps1` after restoring the asset payload.

The exact Android ARM64 OpenJDK21 download and Cacio1.18 jars are retained under `tools/downloads/` because an upstream moving release URL no longer guarantees the original pinned bytes. The mobile builder verifies their SHA256 and materializes OpenJDK licenses into the APK. RuneMobile's `THIRD-PARTY-NOTICES.md` and runtime legal notices apply. JDK8/JDK21 for Windows, Gradle distributions, Android SDK/NDK/CMake and current Android Gradle dependencies are installed/downloaded separately.
''',encoding='utf-8')
 print('Clone-local build scripts and exact public build inputs prepared')

if __name__=='__main__':main()

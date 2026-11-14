$ErrorActionPreference = 'Stop'
$workspace = Split-Path $PSScriptRoot -Parent
$idea = Join-Path $PSScriptRoot 'intellij'
$config = Join-Path $PSScriptRoot 'intellij-config'
$project = Join-Path $workspace 'kronos-osrs-184/Kronos-master'
$jdk = (Get-ChildItem (Join-Path $PSScriptRoot 'jdk8') -Directory | Select-Object -First 1).FullName.Replace('\', '/')
$escapedJdk = [Security.SecurityElement]::Escape($jdk)
$escapedProject = [Security.SecurityElement]::Escape($project.Replace('\', '/'))
$escapedGradleHome = [Security.SecurityElement]::Escape((Join-Path $workspace '.gradle-home').Replace('\', '/'))
New-Item -ItemType Directory -Force -Path "$config/options", "$project/.idea", "$workspace/local-runtime/home/Desktop/kronos" | Out-Null
@'

# Keep this portable installation's settings and indexes alongside its binaries.
idea.config.path=${idea.home.path}/../intellij-config
idea.system.path=${idea.home.path}/../intellij-system
idea.plugins.path=${idea.home.path}/../intellij-config/plugins
idea.log.path=${idea.home.path}/../intellij-system/log
'@ | Add-Content (Join-Path $idea 'bin/idea.properties')
@"
<application>
  <component name="ProjectJdkTable">
    <jdk version="2">
      <name value="Kronos Java 8" />
      <type value="JavaSDK" />
      <version value="java version &quot;1.8.0_512&quot;" />
      <homePath value="$escapedJdk" />
      <roots>
        <annotationsPath><root type="composite" /></annotationsPath>
        <classPath><root type="composite"><root type="simple" url="jar://$escapedJdk/jre/lib/rt.jar!/" /></root></classPath>
        <javadocPath><root type="composite" /></javadocPath>
        <sourcePath><root type="composite"><root type="simple" url="jar://$escapedJdk/src.zip!/" /></root></sourcePath>
      </roots>
    </jdk>
  </component>
</application>
"@ | Set-Content "$config/options/jdk.table.xml"
@'
<?xml version="1.0" encoding="UTF-8"?>
<project version="4">
  <component name="ProjectRootManager" version="2" languageLevel="JDK_1_8" project-jdk-name="Kronos Java 8" project-jdk-type="JavaSDK" />
</project>
'@ | Set-Content "$project/.idea/misc.xml"
@"
<?xml version="1.0" encoding="UTF-8"?>
<project version="4">
  <component name="GradleSettings">
    <option name="serviceDirectoryPath" value="$escapedGradleHome" />
    <option name="linkedExternalProjectsSettings">
      <GradleProjectSettings>
        <option name="externalProjectPath" value="$escapedProject" />
        <option name="gradleJvm" value="Kronos Java 8" />
        <option name="distributionType" value="DEFAULT_WRAPPED" />
      </GradleProjectSettings>
      <GradleProjectSettings>
        <option name="externalProjectPath" value="$escapedProject/runelite" />
        <option name="gradleJvm" value="Kronos Java 8" />
        <option name="distributionType" value="LOCAL" />
        <option name="gradleHome" value="$([Security.SecurityElement]::Escape((Get-ChildItem (Join-Path $workspace '.gradle-home/wrapper/dists/gradle-6.4.1-all') -Recurse -Directory -Filter 'gradle-6.4.1' | Select-Object -First 1).FullName.Replace('\','/')))" />
      </GradleProjectSettings>
    </option>
  </component>
</project>
"@ | Set-Content "$project/.idea/gradle.xml"
$shell = New-Object -ComObject WScript.Shell
$desktop = [Environment]::GetFolderPath('Desktop')
$startMenu = Join-Path ([Environment]::GetFolderPath('Programs')) 'Kronos Local'
New-Item -ItemType Directory -Force -Path $startMenu | Out-Null
foreach ($destination in @($desktop, $startMenu)) {
    foreach ($entry in @(
        @{ Name = 'Play Kronos Local'; Target = 'Start-Kronos.cmd' },
        @{ Name = 'Stop Kronos Local'; Target = 'Stop-Kronos.cmd' },
        @{ Name = 'IntelliJ - Kronos'; Target = 'Open-IntelliJ.cmd' }
    )) {
        $shortcut = $shell.CreateShortcut((Join-Path $destination ($entry.Name + '.lnk')))
        $shortcut.TargetPath = Join-Path $workspace $entry.Target
        $shortcut.WorkingDirectory = $workspace
        $shortcut.IconLocation = (Join-Path $idea 'bin/idea64.exe') + ',0'
        $shortcut.Save()
    }
}
Write-Output 'Configured IntelliJ Java 8 SDK, both Gradle projects, and desktop/Start Menu shortcuts.'
Start-Process -FilePath (Join-Path $idea 'bin/idea64.exe') -ArgumentList ('"' + $project + '"') -WindowStyle Hidden

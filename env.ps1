# PowerShell version of env.sh. Dot-source it:  . .\env.ps1   (portable JDK + Gradle in ~/.jdks)
$env:JAVA_HOME = "$HOME\.jdks\jdk-21.0.13+9"
$env:Path = "$env:JAVA_HOME\bin;$HOME\.jdks\gradle-8.10.2\bin;$env:Path"

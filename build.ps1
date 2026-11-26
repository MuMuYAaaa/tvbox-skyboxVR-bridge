param(
    [Parameter(Mandatory=$true)][string]$InputApk,
    [Parameter(Mandatory=$true)][string]$ApktoolJar,
    [string]$JdkDirectory = $env:JAVA_HOME,
    [string]$AndroidSdkDirectory = $env:ANDROID_HOME,
    [string]$PythonExecutable = 'python',
    [string]$Keystore,
    [string]$PasswordFile,
    [string]$KeyAlias = 'tvbox-skybox',
    [switch]$SkipTests
)
$ErrorActionPreference = 'Stop'
$taskRoot = $PSScriptRoot
if (-not $JdkDirectory) { throw 'Pass -JdkDirectory or set JAVA_HOME to JDK 17 or 18.' }
if (-not $AndroidSdkDirectory) { $AndroidSdkDirectory = $env:ANDROID_SDK_ROOT }
if (-not $AndroidSdkDirectory) { throw 'Pass -AndroidSdkDirectory or set ANDROID_HOME.' }
$InputApk = (Resolve-Path -LiteralPath $InputApk).Path
$ApktoolJar = (Resolve-Path -LiteralPath $ApktoolJar).Path
$expectedHash = 'C3AEE40419FE9CEF88DBAC99AA7AFF6E26503F8FB5E3DD1DD07E46E8E28B3C02'
if ((Get-FileHash -LiteralPath $InputApk -Algorithm SHA256).Hash -ne $expectedHash) { throw 'This patch only supports the exact APK identified in README.md.' }
$buildTools = Join-Path $AndroidSdkDirectory 'build-tools\34.0.0'
$androidJar = Join-Path $AndroidSdkDirectory 'platforms\android-34\android.jar'
$java = Join-Path $JdkDirectory 'bin\java.exe'
$javac = Join-Path $JdkDirectory 'bin\javac.exe'
foreach ($required in @($java,$javac,$androidJar,(Join-Path $buildTools 'lib\d8.jar'),(Join-Path $buildTools 'lib\apksigner.jar'),(Join-Path $buildTools 'zipalign.exe'))) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing build dependency: $required" }
}
function Invoke-Checked {
    param([string]$Executable,[string[]]$Arguments)
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Build step failed: $Executable ($LASTEXITCODE)" }
}
function Save-PreviousDirectory {
    param([string]$Directory)
    if (-not (Test-Path -LiteralPath $Directory)) { return }
    $destination = Join-Path $taskRoot ('build\previous-' + (Split-Path $Directory -Leaf) + '-' + [guid]::NewGuid().ToString('N'))
    $workspacePrefix = [IO.Path]::GetFullPath($taskRoot).TrimEnd('\') + '\'
    foreach ($checked in @($Directory,$destination)) {
        if (-not [IO.Path]::GetFullPath($checked).StartsWith($workspacePrefix,[StringComparison]::OrdinalIgnoreCase)) { throw 'Build directories must stay inside the project.' }
    }
    Move-Item -LiteralPath $Directory -Destination $destination
}
foreach ($directory in @('build','build\classes','build\dex','tools\framework','dist','signing')) {
    New-Item -ItemType Directory -Path (Join-Path $taskRoot $directory) -Force | Out-Null
}
$originalApk = Join-Path $taskRoot 'original.apk'
if (-not [IO.Path]::GetFullPath($InputApk).Equals([IO.Path]::GetFullPath($originalApk),[StringComparison]::OrdinalIgnoreCase)) {
    Copy-Item -LiteralPath $InputApk -Destination $originalApk
}
$previousJavaHome = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $JdkDirectory
    if (-not $SkipTests) {
        Invoke-Checked $PythonExecutable @((Join-Path $taskRoot 'tests\test_handoff.py'))
        Invoke-Checked $PythonExecutable @((Join-Path $taskRoot 'tests\test_stream_relay.py'))
    }
    Save-PreviousDirectory (Join-Path $taskRoot 'decoded')
    Invoke-Checked $java @('-jar',$ApktoolJar,'d',$originalApk,'-o',(Join-Path $taskRoot 'decoded'),'-p',(Join-Path $taskRoot 'tools\framework'))
    Invoke-Checked $PythonExecutable @((Join-Path $taskRoot 'patch.py'))
    Invoke-Checked $PythonExecutable @((Join-Path $taskRoot 'patch_v2.py'))
    $sourceFiles = @(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'src\com\github\tvbox\osc\skybox') -Filter '*.java' | ForEach-Object FullName)
    $compilerArguments = @('--release','8','-Xlint:-options','-encoding','UTF-8','-cp',$androidJar,'-d',(Join-Path $taskRoot 'build\classes')) + $sourceFiles
    Invoke-Checked $javac $compilerArguments
    Invoke-Checked (Join-Path $JdkDirectory 'bin\jar.exe') @('--create','--file',(Join-Path $taskRoot 'build\skybox-helper.jar'),'-C',(Join-Path $taskRoot 'build\classes'),'.')
    Invoke-Checked $java @('-cp',(Join-Path $buildTools 'lib\d8.jar'),'com.android.tools.r8.D8','--min-api','21','--lib',$androidJar,'--output',(Join-Path $taskRoot 'build\dex'),(Join-Path $taskRoot 'build\skybox-helper.jar'))
    Invoke-Checked $java @('-jar',$ApktoolJar,'b',(Join-Path $taskRoot 'decoded'),'-p',(Join-Path $taskRoot 'tools\framework'),'-o',(Join-Path $taskRoot 'build\tvbox-patched-base.apk'))
    Invoke-Checked $PythonExecutable @((Join-Path $taskRoot 'package_apk.py'))
    Invoke-Checked (Join-Path $buildTools 'zipalign.exe') @('-f','-p','4',(Join-Path $taskRoot 'build\tvbox-unsigned.apk'),(Join-Path $taskRoot 'build\tvbox-aligned.apk'))
    if ([bool]$Keystore -ne [bool]$PasswordFile) { throw 'Pass both -Keystore and -PasswordFile, or omit both to use a local generated key.' }
    if (-not $Keystore) {
        $Keystore = Join-Path $taskRoot 'signing\local.p12'
        $PasswordFile = Join-Path $taskRoot 'signing\local-password.txt'
        if (-not (Test-Path -LiteralPath $Keystore)) {
            if (Test-Path -LiteralPath $PasswordFile) { throw 'A password file exists without a keystore; recover or choose another signing pair.' }
            $keyBytes = New-Object byte[] 24
            $random = [Security.Cryptography.RandomNumberGenerator]::Create()
            try { $random.GetBytes($keyBytes) } finally { $random.Dispose() }
            [IO.File]::WriteAllText($PasswordFile,([BitConverter]::ToString($keyBytes).Replace('-','')))
            Invoke-Checked (Join-Path $JdkDirectory 'bin\keytool.exe') @('-genkeypair','-keystore',$Keystore,'-storetype','PKCS12','-storepass:file',$PasswordFile,'-keypass:file',$PasswordFile,'-alias',$KeyAlias,'-keyalg','RSA','-keysize','3072','-validity','10000','-dname','CN=TVBox Skybox Bridge Local Build')
        }
    }
    $Keystore = (Resolve-Path -LiteralPath $Keystore).Path
    $PasswordFile = (Resolve-Path -LiteralPath $PasswordFile).Path
    $outputApk = Join-Path $taskRoot 'dist\TVBox-Skybox-Bridge-v0.2.0-arm64.apk'
    Invoke-Checked $java @('-jar',(Join-Path $buildTools 'lib\apksigner.jar'),'sign','--ks',$Keystore,'--ks-pass',('file:'+$PasswordFile),'--ks-key-alias',$KeyAlias,'--v1-signing-enabled','true','--v2-signing-enabled','true','--v3-signing-enabled','true','--out',$outputApk,(Join-Path $taskRoot 'build\tvbox-aligned.apk'))
    Invoke-Checked $java @('-jar',(Join-Path $buildTools 'lib\apksigner.jar'),'verify',$outputApk)
    Invoke-Checked (Join-Path $buildTools 'zipalign.exe') @('-c','-p','4',$outputApk)
    Save-PreviousDirectory (Join-Path $taskRoot 'verification')
    Invoke-Checked $java @('-jar',$ApktoolJar,'d',$outputApk,'-o',(Join-Path $taskRoot 'verification'),'-p',(Join-Path $taskRoot 'tools\framework'))
    Invoke-Checked $PythonExecutable @((Join-Path $taskRoot 'verify_apk.py'),'--apk',$outputApk)
    Write-Output "Built and verified: $outputApk"
} finally {
    $env:JAVA_HOME = $previousJavaHome
}

[CmdletBinding(DefaultParameterSetName='Task')]
param(
    [Parameter(Mandatory=$true,ParameterSetName='Task')][string]$TaskFile,
    [Parameter(Mandatory=$true,ParameterSetName='Probe')][switch]$Probe,
    [switch]$DryRun
)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$codeCommand=Get-Command code.cmd -ErrorAction SilentlyContinue
$code=if($codeCommand){$codeCommand.Source}else{Join-Path $env:LOCALAPPDATA 'Programs\Microsoft VS Code\bin\code.cmd'}
if(-not(Test-Path -LiteralPath $code)){throw 'VS Code CLI was not found. Install VS Code or add code.cmd to PATH.'}
$runId=(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,8)
$runRoot=Join-Path $root "local.copilot-runs\$runId"
$resultFile=Join-Path $runRoot 'result.md'
$launchFile=Join-Path $runRoot 'launch.json'
if($Probe){
    $task=Join-Path $runRoot 'task.md'
}else{
    $candidate=if([IO.Path]::IsPathRooted($TaskFile)){$TaskFile}else{Join-Path $root $TaskFile}
    $task=(Resolve-Path -LiteralPath $candidate).Path
    if(-not(Test-Path -LiteralPath $task -PathType Leaf)){throw 'TaskFile must be an existing file.'}
    if([IO.Path]::GetExtension($task) -ne '.md'){throw 'Use a Markdown task file.'}
}
$prompt="Execute the attached task file in this workspace using GitHub Copilot. Read AGENTS.md first. This run ID is $runId. Write your final status (STATUS: completed or STATUS: blocked), changes, exact checks and remaining issues to $resultFile. Follow the scope and Git instructions in the task file. Do not start another worker for the same files."
$launch=[ordered]@{runId=$runId;workspace=$root;taskFile=$task;resultFile=$resultFile;state='prepared';cli=$code;createdAt=[DateTimeOffset]::Now.ToString('o')}
if($DryRun){$launch|ConvertTo-Json;return}
New-Item -ItemType Directory -Path $runRoot -Force|Out-Null
$utf8=[Text.UTF8Encoding]::new($false)
if($Probe){
    $body=@"
# Copilot dispatch probe

Only verify this dispatch. Read watch/src/manifest.json and report this exact run ID: $runId.
Write the absolute workspace path, watch package name and version, and STATUS: completed to $resultFile.
Only modify this ignored result file. Do not edit application files, commit, push, build, or perform the older development handoff.
"@
    [IO.File]::WriteAllText($task,$body,$utf8)
}
[IO.File]::WriteAllText($launchFile,($launch|ConvertTo-Json),$utf8)
Push-Location $root
try{
    # Focus/open this repository before using --reuse-window; do not target an unrelated project.
    & $code $root
    if($LASTEXITCODE -ne 0){throw "VS Code workspace open failed ($LASTEXITCODE)."}
    Start-Sleep -Milliseconds 1200
    & $code chat --reuse-window --mode agent --add-file $task --add-file (Join-Path $root 'AGENTS.md') $prompt
    if($LASTEXITCODE -ne 0){throw "VS Code chat dispatch failed ($LASTEXITCODE)."}
    $launch.state='dispatched'
}catch{
    $launch.state='dispatch_failed'
    throw
}finally{
    Pop-Location
    [IO.File]::WriteAllText($launchFile,($launch|ConvertTo-Json),$utf8)
}
$launch|ConvertTo-Json
Write-Output 'Dispatched is not completion. Check the result file and the Copilot session before reporting success.'

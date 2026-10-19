$ErrorActionPreference = "Stop"

$sessionId = "ses_f61704e30ffeyVdFdECGtBb2L7"
$projectPath = "D:\coding"
$model = "openai/gpt-5.6-sol"
$smallModel = "openai/gpt-5.4-mini"

$childConfig = @{
    enabled_providers = @("openai")
    model = $model
    small_model = $smallModel
} | ConvertTo-Json -Compress

$command = @"
`$env:OPENCODE_CONFIG_CONTENT = '$childConfig'
Set-Location -LiteralPath '$projectPath'
opencode --session '$sessionId' --model '$model'
"@
$encodedCommand = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))

Start-Process -FilePath "powershell.exe" -ArgumentList "-NoExit", "-EncodedCommand", $encodedCommand

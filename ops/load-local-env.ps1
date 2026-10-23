param([string]$Path = (Join-Path (Split-Path $PSScriptRoot -Parent) '.env.local'))
# Dot-source this file. It loads literal KEY=value pairs; never executes file content.
$ErrorActionPreference = 'Stop'
foreach ($line in [IO.File]::ReadAllLines($Path)) {
    if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) { continue }
    $separator = $line.IndexOf('=')
    if ($separator -lt 1) { throw 'Invalid local environment line' }
    $name = $line.Substring(0,$separator)
    if ($name -notmatch '^[A-Z][A-Z0-9_]*$') { throw 'Invalid environment key' }
    [Environment]::SetEnvironmentVariable($name,$line.Substring($separator+1),'Process')
}

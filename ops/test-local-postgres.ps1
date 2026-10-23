param([string]$PgBin = 'C:\Program Files\PostgreSQL\17\bin', [int]$Port = 55439, [switch]$CheckBootstrap)
$ErrorActionPreference = 'Stop'
$repoPath = Split-Path $PSScriptRoot -Parent
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$testPath = Join-Path $tempRoot ('arequipa-tests-' + [guid]::NewGuid().ToString('N'))
$saved = @{}
foreach ($name in @('TEST_DB_URL','TEST_DB_USERNAME','TEST_DB_PASSWORD','DB_URL','MIGRATION_DB_USERNAME','MIGRATION_DB_PASSWORD')) { $saved[$name] = [Environment]::GetEnvironmentVariable($name) }
$started = $false
try {
    & "$PgBin\initdb.exe" -D $testPath -U codex_test -A trust --encoding=UTF8 --locale=C
    if ($LASTEXITCODE -ne 0) { throw 'initdb failed' }
    # Only this disposable loopback test instance uses trust, never deployment.
    # Avoid inherited PowerShell output pipes keeping pg_ctl open after it starts postgres.
    $pgCtlArgs = @('-D', ('"{0}"' -f $testPath), '-l',
        ('"{0}"' -f (Join-Path $testPath 'postgres.log')), '-o',
        ('"-h 127.0.0.1 -p {0}"' -f $Port), '-w', 'start')
    $pgCtlProcess = Start-Process -FilePath "$PgBin\pg_ctl.exe" -ArgumentList $pgCtlArgs -WindowStyle Hidden -PassThru
    if (-not $pgCtlProcess.WaitForExit(30000) -or $pgCtlProcess.ExitCode -ne 0) { throw 'PostgreSQL start failed' }
    $started = $true
    & "$PgBin\createdb.exe" -h 127.0.0.1 -p $Port -U codex_test accesible_test
    if ($LASTEXITCODE -ne 0) { throw 'createdb failed' }
    $env:TEST_DB_URL = "jdbc:postgresql://127.0.0.1:$Port/accesible_test"
    $env:TEST_DB_USERNAME = 'codex_test'
    $env:TEST_DB_PASSWORD = ''
    Push-Location $repoPath
    try {
        if ($CheckBootstrap) {
            & "$PgBin\createdb.exe" -h 127.0.0.1 -p $Port -U codex_test bootstrap_test
            if ($LASTEXITCODE -ne 0) { throw 'Bootstrap database creation failed' }
            $bootstrapSql = "BEGIN;`nCREATE SCHEMA accesible;`nSET LOCAL search_path=accesible,pg_catalog;`n"
            foreach ($migration in (Get-ChildItem -LiteralPath 'src/main/resources/db/migration' -Filter 'V*.sql' | Sort-Object Name)) {
                $bootstrapSql += [IO.File]::ReadAllText($migration.FullName) + "`n"
            }
            $bootstrapSql += "COMMIT;`n"
            $bootstrapPath = Join-Path $testPath 'bootstrap.sql'
            [IO.File]::WriteAllText($bootstrapPath,$bootstrapSql,[Text.UTF8Encoding]::new($false))
            & "$PgBin\psql.exe" -h 127.0.0.1 -p $Port -U codex_test -d bootstrap_test -v ON_ERROR_STOP=1 -f $bootstrapPath
            if ($LASTEXITCODE -ne 0) { throw 'Bootstrap SQL failed' }
            $env:DB_URL = "jdbc:postgresql://127.0.0.1:$Port/bootstrap_test"
            $env:MIGRATION_DB_USERNAME = 'codex_test'
            $env:MIGRATION_DB_PASSWORD = ''
            & .\mvnw.cmd -B flyway:baseline '-Dflyway.baselineVersion=3'
            if ($LASTEXITCODE -ne 0) { throw 'Bootstrap baseline failed' }
            & .\mvnw.cmd -B flyway:validate flyway:migrate
            if ($LASTEXITCODE -ne 0) { throw 'Bootstrap adoption failed' }
        }
        & .\mvnw.cmd -B verify
        $result = $LASTEXITCODE
    } finally { Pop-Location }
    if ($result -ne 0) { throw "Maven verify failed ($result)" }
} finally {
    if ($started) { & "$PgBin\pg_ctl.exe" -D $testPath -m fast -w stop }
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name,$saved[$name]) }
    $resolved = [IO.Path]::GetFullPath($testPath)
    if ($resolved.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path $resolved -Leaf) -match '^arequipa-tests-[a-f0-9]{32}$') {
        Remove-Item -LiteralPath $resolved -Recurse -Force -ErrorAction SilentlyContinue
    }
}

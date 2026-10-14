# Shared by backup.ps1, restore.ps1 and update.ps1 (dot-sourced). Every Docker command names the
# compose file next to those scripts, so it acts on the same project `docker compose -f
# deploy/compose/compose.yaml` does: zm-baton-dev unless COMPOSE_PROJECT_NAME says otherwise.

$ErrorActionPreference = 'Stop'
$script:ComposeFile = (Resolve-Path (Join-Path $PSScriptRoot '..' 'compose.yaml')).Path
$script:RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..' '..' '..')).Path
$script:MigrationDir = Join-Path $script:RepoRoot 'apps' 'server' 'src' 'main' 'resources' 'db' 'migration'
# Docker Compose builds from the checkout's root without asking for more file access only when it
# runs there, so the scripts work from it; Pop-Location at the end returns to where they started.
Push-Location -LiteralPath $script:RepoRoot

function Write-Step([string] $Text) { Write-Host "== $Text" }

# Runs `docker compose -f compose.yaml <args>`; throws with [Message] when it fails.
function Invoke-Compose([string[]] $Arguments, [string] $Message = 'docker compose failed') {
	& docker compose -f $script:ComposeFile @Arguments
	if ($LASTEXITCODE -ne 0) { throw "$Message (docker compose $($Arguments -join ' '), exit $LASTEXITCODE)" }
}

# The resolved compose model with the backup service included. It holds the database password,
# so it is only read here and never printed.
function Get-ComposeModel {
	$raw = & docker compose -f $script:ComposeFile --profile backup config --format json
	if ($LASTEXITCODE -ne 0) { throw 'docker compose config failed: is Docker running, and is ZM_BATON_DB_PASSWORD set in deploy/compose/.env?' }
	return ($raw -join "`n") | ConvertFrom-Json
}

function Get-ProjectName { (Get-ComposeModel).name }

# The folder on this PC that the backup service writes to.
function Get-BackupDir {
	$mount = (Get-ComposeModel).services.backup.volumes | Where-Object { $_.target -eq '/backups' } | Select-Object -First 1
	if (-not $mount) { throw 'the backup service has no /backups folder in compose.yaml' }
	return $mount.source
}

function Get-RunningServices {
	$names = & docker compose -f $script:ComposeFile --profile app --profile backup ps --status running --services
	if ($LASTEXITCODE -ne 0) { throw 'docker compose ps failed: is Docker running?' }
	return @($names | Where-Object { $_ })
}

# Runs one command of ops/zmb-backup.sh in a fresh container of the backup service and returns
# its standard output lines; its log lines go to the screen. [Mounts] are extra -v values.
function Invoke-Ops([string[]] $Command, [string[]] $Mounts = @()) {
	$volumeArgs = @()
	foreach ($m in $Mounts) { $volumeArgs += @('-v', $m) }
	$out = & docker compose -f $script:ComposeFile --profile backup run --rm --no-deps -T @volumeArgs backup @Command
	if ($LASTEXITCODE -ne 0) { throw "the backup container failed: $($Command -join ' ') (exit $LASTEXITCODE)" }
	return @($out | Where-Object { $_ })
}

function Assert-DatabaseRunning {
	if ((Get-RunningServices) -notcontains 'postgres') {
		throw "the database is not running. Start it first: docker compose -f '$script:ComposeFile' up -d postgres"
	}
}

# A new backup of the given kind; returns its full path on this PC.
function New-Backup([string] $Kind) {
	$name = Invoke-Ops @('once', $Kind) | Select-Object -Last 1
	if (-not $name -or $name -notmatch '^zm-baton-[a-z-]+-\d{8}T\d{6}Z\.dump$') { throw "the backup gave no file name (got '$name')" }
	return (Join-Path (Get-BackupDir) $name)
}

# Migration versions in this checkout, as strings like "3", sorted by number.
function Get-LocalMigrations {
	Get-ChildItem -LiteralPath $script:MigrationDir -Filter 'V*__*.sql' |
		ForEach-Object { if ($_.Name -match '^V(\d+)__(.+)\.sql$') { [pscustomobject]@{ Version = $Matches[1]; Name = $_.Name } } } |
		Sort-Object { [int]$_.Version }
}

# Migration versions the database has applied, oldest first. Empty for a new database.
function Get-AppliedMigrations { @(Invoke-Ops @('versions')) }

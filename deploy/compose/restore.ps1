<#
.SYNOPSIS
Replaces the zm-baton database with a backup file (FN-1403). Everything recorded after that
backup is lost, so the script takes one more backup first.

.DESCRIPTION
1. Refuses unless -ConfirmProject names this compose project (zm-baton-dev unless
   COMPOSE_PROJECT_NAME says otherwise): the name shows which database is about to be replaced.
2. Refuses unless the database is running and the server and the daily backup are stopped, so
   nothing writes while the database is replaced.
3. Checks that the file is a backup pg_restore can read and that it matches its .sha256 file.
4. Takes a backup of the current database: zm-baton-before-restore-<UTC time>.dump.
5. Restores the file into a new database and, only when that worked, swaps it in for the old one.
   A failed restore leaves the current database as it was.
6. Writes an audit row `backup.restored` with the file name and its checksum, compares the
   restored migration versions with this checkout's, and prints how to start the server again.

Prerequisites: Docker running, deploy/compose/.env with ZM_BATON_DB_PASSWORD, PowerShell 7.

.EXAMPLE
docker compose -f deploy/compose/compose.yaml stop server backup
pwsh deploy/compose/restore.ps1 -File D:/zm-baton-backups/zm-baton-daily-20261014T030000Z.dump -ConfirmProject zm-baton-dev
docker compose -f deploy/compose/compose.yaml --profile app up -d
#>
param(
	[string] $File,
	[string] $ConfirmProject
)

# Resolved before host.ps1 moves to the checkout's root, so a relative path means what it says.
$path = if ($File) { (Resolve-Path -LiteralPath $File -ErrorAction SilentlyContinue).Path } else { $null }

. (Join-Path $PSScriptRoot 'ops' 'host.ps1')

try {
	if (-not $File) { throw 'use: restore.ps1 -File <backup file> -ConfirmProject <compose project name>' }
	if (-not $path -or -not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "no backup file $File" }
	$project = Get-ProjectName
	Write-Step "compose project: $project"
	if ($ConfirmProject -cne $project) {
		throw "this replaces the database of the compose project '$project'. Run again with -ConfirmProject $project to confirm"
	}
	Assert-DatabaseRunning
	$running = Get-RunningServices | Where-Object { $_ -in @('server', 'backup') }
	if ($running) {
		throw "stop the server and the daily backup first: docker compose -f '$script:ComposeFile' stop server backup (running: $($running -join ', '))"
	}

	$folder = Split-Path -Parent $path
	$leaf = Split-Path -Leaf $path
	$mount = "${folder}:/restore:ro"
	Write-Step "checking $path"
	Invoke-Ops @('check', "/restore/$leaf") @($mount) | Out-Null

	Write-Step 'backing up the current database first'
	$before = New-Backup 'before-restore'
	Write-Step "current database saved as $before"

	Write-Step "restoring $leaf"
	Invoke-Ops @('restore', "/restore/$leaf") @($mount) | Out-Null

	$applied = Get-AppliedMigrations
	$local = @(Get-LocalMigrations | ForEach-Object { $_.Version })
	$unknown = @($applied | Where-Object { $_ -notin $local })
	$pending = @($local | Where-Object { $_ -notin $applied })
	Write-Step "restored. Migration versions in the database: $(if ($applied) { $applied -join ', ' } else { 'none' })"
	if ($unknown) {
		Write-Host "warning: this backup has migrations this checkout does not know ($($unknown -join ', ')). The server starts, but code older than the backup may fail on its tables. Start the code the backup came from, or restore $before." -ForegroundColor Yellow
	}
	if ($pending) { Write-Step "the server applies $($pending -join ', ') when it starts" }
	Write-Step "start the server again: docker compose -f '$script:ComposeFile' --profile app up -d"
	Write-Step "to undo this restore: restore.ps1 -File '$before' -ConfirmProject $project"
	exit 0
} catch {
	Write-Host "restore failed: $($_.Exception.Message)" -ForegroundColor Red
	exit 1
} finally {
	Pop-Location
}

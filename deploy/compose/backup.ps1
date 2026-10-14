<#
.SYNOPSIS
Takes one backup of the zm-baton database now (FN-1403).

.DESCRIPTION
1. Checks that the database container of this compose project is running.
2. Runs pg_dump in a one-off container of the `backup` service. The dump goes to a hidden partial
   file, is read back with pg_restore, gets a .sha256 file and only then its name:
   zm-baton-manual-<UTC time>.dump in the backup folder (ZM_BATON_BACKUP_DIR, by default the
   folder zm-baton-backups next to this checkout).
3. Prints the file's full path. The daily rotation never deletes manual backups.

Reading only: the database is not changed and the server keeps running.
Prerequisites: Docker running, deploy/compose/.env with ZM_BATON_DB_PASSWORD, PowerShell 7.

.EXAMPLE
pwsh deploy/compose/backup.ps1
#>
param()

. (Join-Path $PSScriptRoot 'ops' 'host.ps1')

try {
	$project = Get-ProjectName
	Write-Step "compose project: $project"
	Assert-DatabaseRunning
	$file = New-Backup 'manual'
	Write-Step "backup written: $file"
	exit 0
} catch {
	Write-Host "backup failed: $($_.Exception.Message)" -ForegroundColor Red
	exit 1
} finally {
	Pop-Location
}

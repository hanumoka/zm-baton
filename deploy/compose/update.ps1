<#
.SYNOPSIS
Updates the running zm-baton to the code checked out here, after a backup (FN-1403, G30.2).

.DESCRIPTION
It never rolls back on its own; when something fails it stops and prints how to roll back.
1. Shows the commit it builds and the migrations the server will apply: the ones in this checkout
   that the database has not applied yet. Stops if the database has migrations this checkout does
   not know (the checkout is older than the database).
2. Stops if a run is still alive, unless -IgnoreLiveRuns: update between runs (G30.2).
3. Takes a backup: zm-baton-before-update-<UTC time>.dump. Stops if that fails.
4. Tags the image of the running server as <image>:before-update-<UTC time>, to roll back to.
5. Rebuilds and restarts the server and the daily backup (`--profile app up -d --build`, then
   `restart backup` so it runs this checkout's script). The database volume stays: neither `down`
   nor `-v` is used.
6. Waits until /actuator/health answers UP and the database has every migration of this
   checkout. The server applies migrations before it answers.

Prerequisites: Docker running, deploy/compose/.env with ZM_BATON_DB_PASSWORD, Git, PowerShell 7.
Get the code first (for example `git pull --ff-only`); this script does not change the checkout.

.EXAMPLE
git pull --ff-only
pwsh deploy/compose/update.ps1
#>
param(
	[switch] $IgnoreLiveRuns,
	[int] $TimeoutSeconds = 300
)

. (Join-Path $PSScriptRoot 'ops' 'host.ps1')

$stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMdd'T'HHmmss'Z'")
$backup = $null
$rollbackImage = $null
$pending = @()

function Show-Rollback([string] $Reason) {
	Write-Host "update stopped: $Reason" -ForegroundColor Red
	Write-Host 'Nothing is rolled back on its own. What to do:'
	Write-Host "  1. Read why: docker compose -f '$script:ComposeFile' logs --tail 200 server"
	if ($rollbackImage) {
		Write-Host '  2. To go back to the server that ran before:'
		Write-Host "     docker compose -f '$script:ComposeFile' stop server backup"
		if ($pending -and $backup) {
			Write-Host "     pwsh '$PSScriptRoot/restore.ps1' -File '$backup' -ConfirmProject $project"
			Write-Host "       (the new migrations $($pending -join ', ') may have run; this brings the database back to before them,"
			Write-Host '        and records made since the backup are lost)'
		}
		Write-Host "     docker tag $rollbackImage $($rollbackImage -replace ':before-update-[^:]+$', ':latest')"
		Write-Host "     docker compose -f '$script:ComposeFile' --profile app up -d --no-build"
	} elseif ($backup) {
		Write-Host "  2. The database before the update is in $backup (restore.ps1 puts it back)."
	}
}

try {
	$project = Get-ProjectName
	$commit = (& git -C $script:RepoRoot rev-parse --short=12 HEAD)
	if ($LASTEXITCODE -ne 0) { throw "$script:RepoRoot is not a Git checkout" }
	if (& git -C $script:RepoRoot status --porcelain) { $commit += '-dirty'; Write-Host 'note: the checkout has uncommitted changes; they are built too' -ForegroundColor Yellow }
	Write-Step "compose project: $project; building commit $commit"

	if ((Get-RunningServices) -notcontains 'postgres') {
		Write-Step 'starting the database'
		Invoke-Compose @('up', '-d', '--wait', 'postgres') 'the database did not start'
	}

	$applied = Get-AppliedMigrations
	$local = @(Get-LocalMigrations | ForEach-Object { $_.Version })
	$unknown = @($applied | Where-Object { $_ -notin $local })
	if ($unknown) {
		throw "the database has migrations this checkout does not know ($($unknown -join ', ')): the checkout is older than the database. Check out newer code"
	}
	$pending = @($local | Where-Object { $_ -notin $applied })
	$names = @(Get-LocalMigrations | Where-Object { $_.Version -in $pending } | ForEach-Object { $_.Name })
	Write-Step "migrations to apply: $(if ($names) { $names -join ', ' } else { 'none' })"

	$live = [int](Invoke-Ops @('live-runs') | Select-Object -Last 1)
	if ($live -gt 0 -and -not $IgnoreLiveRuns) {
		throw "$live run(s) have not ended. Let them end, or run again with -IgnoreLiveRuns"
	}

	Write-Step 'backing up the database'
	$backup = New-Backup 'before-update'
	Write-Step "backup written: $backup"

	$container = (& docker compose -f $script:ComposeFile --profile app ps -q server) | Select-Object -First 1
	if ($container) {
		$imageId = & docker inspect --format '{{.Image}}' $container
		$imageName = (& docker inspect --format '{{.Config.Image}}' $container) -replace ':[^:/]+$', ''
		$rollbackImage = "${imageName}:before-update-$stamp"
		& docker tag $imageId $rollbackImage
		if ($LASTEXITCODE -ne 0) { throw 'could not tag the running server image' }
		Write-Step "the server that ran before is kept as $rollbackImage"
	}

	Write-Step 'building and starting the new server'
	$env:ZM_BATON_BUILD_COMMIT = $commit
	Invoke-Compose @('--profile', 'app', 'up', '-d', '--build') 'the build or the start failed'
	# The daily backup reads ops/zmb-backup.sh when it starts; a restart makes it run this checkout's.
	Invoke-Compose @('--profile', 'app', 'restart', 'backup') 'the daily backup did not restart'

	$hostPort = (& docker compose -f $script:ComposeFile --profile app port server 18081) | Select-Object -First 1
	if (-not $hostPort) { throw 'the server publishes no port' }
	$url = "http://$hostPort/actuator/health"
	Write-Step "waiting for $url (up to $TimeoutSeconds s)"
	$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
	$up = $false
	while ((Get-Date) -lt $deadline) {
		try {
			if ((Invoke-RestMethod -Uri $url -TimeoutSec 5).status -eq 'UP') { $up = $true; break }
		} catch {
			# Not answering yet: the server is starting or applying migrations.
		}
		Start-Sleep -Seconds 3
	}
	if (-not $up) { throw "the server did not answer UP within $TimeoutSeconds s" }

	$missing = @($local | Where-Object { $_ -notin (Get-AppliedMigrations) })
	if ($missing) { throw "the server answers, but the database lacks migrations $($missing -join ', ')" }
	Write-Step "updated: commit $commit is running and the database has every migration ($($local[-1]))"
	exit 0
} catch {
	Show-Rollback $_.Exception.Message
	exit 1
} finally {
	Pop-Location
}

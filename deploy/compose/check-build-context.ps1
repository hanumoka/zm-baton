# Checks that the server image's build context (apps/) leaves secret files out.
#
# 1. Plants synthetic files under apps/: secret-like names that must stay out, and one plain
#    file that must get in (it proves the probe sees the context at all).
# 2. Builds a probe image from apps/ with a copy of server.Dockerfile.dockerignore and lists
#    which planted files reached it.
# 3. Removes the planted files and the probe image, whatever happened.
#
# Prerequisites: Docker running, PowerShell 7. Run from anywhere:
#   pwsh deploy/compose/check-build-context.ps1
# Exit code 0: only the plain file got in. Anything else: read the printed list.

$ErrorActionPreference = 'Stop'
$apps = (Resolve-Path (Join-Path $PSScriptRoot '../../apps')).Path
$marker = 'SYNTHETIC_CONTEXT_PROBE'
# Only folders that already exist, so cleaning up never has to remove a folder.
$mustStayOut = @(
	'web/.env', 'web/.env.local', 'web/.env.production', 'web/src/.env.development',
	'server/.env', 'server/.env.local', 'server/src/.env',
	'web/dev.pem', 'server/dev.key', 'server/keystore.jks', 'web/client.p12', 'web/.npmrc'
)
$mustGetIn = 'web/context-probe-visible.txt'
$planted = @()
$probe = Join-Path ([IO.Path]::GetTempPath()) ("zm-baton-context-" + [guid]::NewGuid())
$image = 'zm-baton-context-probe:local'
try {
	foreach ($rel in $mustStayOut + $mustGetIn) {
		$path = Join-Path $apps $rel
		if (-not (Test-Path (Split-Path $path))) { throw "folder for $rel does not exist" }
		if (Test-Path $path) { throw "$rel already exists; this check plants its own files and leaves yours alone" }
		Set-Content -LiteralPath $path -Value "$marker $rel" -NoNewline
		$planted += $path
	}
	New-Item -ItemType Directory $probe | Out-Null
	Copy-Item (Join-Path $PSScriptRoot 'server.Dockerfile.dockerignore') (Join-Path $probe 'probe.Dockerfile.dockerignore')
	Set-Content -LiteralPath (Join-Path $probe 'probe.Dockerfile') -Value @(
		'FROM busybox:1.37',
		'COPY . /ctx',
		"RUN grep -rl $marker /ctx | sed 's|^/ctx/||' | sort > /found.txt || true",
		'CMD ["cat", "/found.txt"]'
	)
	docker build --quiet --no-cache -t $image -f (Join-Path $probe 'probe.Dockerfile') $apps | Out-Null
	if ($LASTEXITCODE -ne 0) { throw 'probe build failed' }
	$found = @(docker run --rm $image | Where-Object { $_ })
	if ($LASTEXITCODE -ne 0) { throw 'probe run failed' }
	Write-Output 'Planted files that reached the build context:'
	$found | ForEach-Object { Write-Output "  $_" }
	if ($found.Count -eq 1 -and $found[0] -eq $mustGetIn) {
		Write-Output 'OK: no secret-like file got in, and the plain file did.'
		$code = 0
	} elseif ($found -notcontains $mustGetIn) {
		Write-Output 'BROKEN PROBE: the plain file did not get in, so an empty list proves nothing.'
		$code = 2
	} else {
		Write-Output 'FAIL: secret-like files got into the build context.'
		$code = 1
	}
} finally {
	foreach ($path in $planted) { Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue }
	if (Test-Path $probe) { Remove-Item -LiteralPath $probe -Recurse -Force }
	docker image rm $image 2>$null | Out-Null
}
exit $code

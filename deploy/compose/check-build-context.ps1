# Checks which files server.Dockerfile.dockerignore keeps out of the server image's build context.
#
# 1. Builds a synthetic build context in a new temporary folder: the folders the Dockerfile
#    copies (web/, server/ and their src/), secret-like files that must stay out, and one
#    plain file that must get in (it proves the probe sees the context at all).
# 2. Builds a probe image from that folder with a copy of the ignore file and lists which of
#    the files reached it.
# 3. Removes the temporary folder and the probe image, whatever happened.
#
# Nothing is written in the working tree: the check never touches files a person keeps there.
# Prerequisites: Docker running, PowerShell 7. Run from anywhere:
#   pwsh deploy/compose/check-build-context.ps1
# Exit code 0: only the plain file got in. Anything else: read the printed list.

$ErrorActionPreference = 'Stop'
$marker = 'SYNTHETIC_CONTEXT_PROBE'
$mustStayOut = @(
	'web/.env', 'web/.env.local', 'web/.env.production', 'web/src/.env.development',
	'server/.env', 'server/.env.local', 'server/src/.env',
	'web/dev.pem', 'server/dev.key', 'server/keystore.jks', 'web/client.p12', 'web/.npmrc'
)
$mustGetIn = 'web/context-probe-visible.txt'
$root = Join-Path ([IO.Path]::GetTempPath()) ("zm-baton-context-" + [guid]::NewGuid())
$context = Join-Path $root 'apps'
$image = 'zm-baton-context-probe:local'
$code = 1
try {
	foreach ($rel in $mustStayOut + $mustGetIn) {
		$path = Join-Path $context $rel
		New-Item -ItemType Directory -Force (Split-Path $path) | Out-Null
		Set-Content -LiteralPath $path -Value "$marker $rel" -NoNewline
	}
	Copy-Item (Join-Path $PSScriptRoot 'server.Dockerfile.dockerignore') (Join-Path $root 'probe.Dockerfile.dockerignore')
	Set-Content -LiteralPath (Join-Path $root 'probe.Dockerfile') -Value @(
		'FROM busybox:1.37',
		'COPY . /ctx',
		"RUN grep -rl $marker /ctx | sed 's|^/ctx/||' | sort > /found.txt || true",
		'CMD ["cat", "/found.txt"]'
	)
	docker build --quiet --no-cache -t $image -f (Join-Path $root 'probe.Dockerfile') $context | Out-Null
	if ($LASTEXITCODE -ne 0) { throw 'probe build failed' }
	$found = @(docker run --rm $image | Where-Object { $_ })
	if ($LASTEXITCODE -ne 0) { throw 'probe run failed' }
	Write-Output 'Files that reached the build context:'
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
	if (Test-Path $root) { Remove-Item -LiteralPath $root -Recurse -Force }
	docker image rm $image 2>$null | Out-Null
}
exit $code

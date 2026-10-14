#!/bin/sh
# Updates the running zm-baton to the code checked out here, after a backup (FN-1403, G30.2).
# The same steps as update.ps1; it never rolls back on its own and prints how to when it stops:
# 1. shows the commit and the migrations to apply; stops if the database is newer than the code;
# 2. stops if a run is still alive, unless --ignore-live-runs;
# 3. backs up the database as zm-baton-before-update-<UTC time>.dump;
# 4. tags the running server image as <image>:before-update-<UTC time>;
# 5. rebuilds and restarts (`--profile app up -d --build`; the database volume stays);
# 6. waits for /actuator/health UP and for every migration of this checkout in the database.
# Usage: git pull --ff-only && sh deploy/compose/update.sh [--ignore-live-runs] [--timeout SECONDS]
. "$(dirname -- "$0")/ops/host.sh"

ignore_live=no
timeout=300
while [ $# -gt 0 ]; do
	case "$1" in
	--ignore-live-runs) ignore_live=yes; shift ;;
	--timeout) timeout=${2:-}; shift 2 || die "use: --timeout SECONDS" ;;
	*) die "use: update.sh [--ignore-live-runs] [--timeout SECONDS]" ;;
	esac
done

enter_repo
stamp=$(date -u +%Y%m%dT%H%M%SZ)
backup=
rollback_image=
pending=

stop() {
	printf 'update stopped: %s\n' "$*" >&2
	echo 'Nothing is rolled back on its own. What to do:'
	echo "  1. Read why: docker compose -f '$COMPOSE_FILE' logs --tail 200 server"
	if [ -n "$rollback_image" ]; then
		echo '  2. To go back to the server that ran before:'
		echo "     docker compose -f '$COMPOSE_FILE' stop server backup"
		if [ -n "$pending" ] && [ -n "$backup" ]; then
			echo "     sh '$HERE/restore.sh' --file '$backup' --confirm-project $project"
			echo "       (the new migrations $(printf '%s' "$pending" | tr '\n' ' ')may have run; this brings the database back to before"
			echo '        them, and records made since the backup are lost)'
		fi
		echo "     docker tag $rollback_image ${rollback_image%:before-update-*}:latest"
		echo "     docker compose -f '$COMPOSE_FILE' --profile app up -d --no-build"
	elif [ -n "$backup" ]; then
		echo "  2. The database before the update is in $backup (restore.sh puts it back)."
	fi
	exit 1
}

load_model
project=$(project_name)
commit=$(git -C "$REPO_ROOT" rev-parse --short=12 HEAD) || stop "$REPO_ROOT is not a Git checkout"
if [ -n "$(git -C "$REPO_ROOT" status --porcelain)" ]; then
	commit="$commit-dirty"
	echo 'note: the checkout has uncommitted changes; they are built too'
fi
step "compose project: $project; building commit $commit"

if ! is_running postgres; then
	step 'starting the database'
	compose up -d --wait postgres || stop 'the database did not start'
fi

applied=$(applied_migrations) || stop 'could not read the database'
ours=$(local_migrations)
for v in $applied; do
	in_list "$v" "$ours" || stop "the database has migration $v, which this checkout does not know: the checkout is older than the database"
done
for v in $ours; do
	in_list "$v" "$applied" || pending="$pending$v
"
done
step "migrations to apply: $(if [ -n "$pending" ]; then printf '%s' "$pending" | tr '\n' ' '; else echo none; fi)"

live=$(ops live-runs | tail -n 1)
case "$live" in '' | *[!0-9]*) stop "could not count the live runs (got '$live')" ;; esac
if [ "$live" -gt 0 ] && [ "$ignore_live" = no ]; then
	stop "$live run(s) have not ended. Let them end, or run again with --ignore-live-runs"
fi

step 'backing up the database'
backup=$(new_backup before-update) || stop 'the backup failed'
step "backup written: $backup"

container=$(compose --profile app ps -q server | head -n 1)
if [ -n "$container" ]; then
	image_id=$(docker inspect --format '{{.Image}}' "$container")
	image_name=$(docker inspect --format '{{.Config.Image}}' "$container" | sed 's/:[^:/]*$//')
	rollback_image="$image_name:before-update-$stamp"
	docker tag "$image_id" "$rollback_image" || stop 'could not tag the running server image'
	step "the server that ran before is kept as $rollback_image"
fi

step 'building and starting the new server'
export ZM_BATON_BUILD_COMMIT="$commit"
compose --profile app up -d --build || stop 'the build or the start failed'
# The daily backup reads ops/zmb-backup.sh when it starts; a restart makes it run this checkout's.
compose --profile app restart backup || stop 'the daily backup did not restart'

host_port=$(compose --profile app port server 18081 | head -n 1)
[ -n "$host_port" ] || stop 'the server publishes no port'
url="http://$host_port/actuator/health"
step "waiting for $url (up to $timeout s)"
waited=0
until curl -fsS --max-time 5 "$url" 2>/dev/null | grep -q '"status":"UP"'; do
	[ "$waited" -lt "$timeout" ] || stop "the server did not answer UP within $timeout s"
	sleep 3
	waited=$((waited + 3))
done

applied=$(applied_migrations) || stop 'could not read the database'
for v in $ours; do
	in_list "$v" "$applied" || stop "the server answers, but the database lacks migration $v"
done
step "updated: commit $commit is running and the database has every migration"

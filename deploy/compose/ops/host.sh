# Shared by backup.sh, restore.sh and update.sh (sourced). The same steps as host.ps1 for Linux
# and macOS. Every Docker command names the compose file next to those scripts, so it acts on the
# same project `docker compose -f deploy/compose/compose.yaml` does.

set -u
# abs_dir DIR: DIR as an absolute path Docker understands. Git Bash on Windows gives C:/... form
# (pwd -W), because the next line stops it from rewriting /c/... paths for Docker.
abs_dir() { (CDPATH= cd -- "$1" && { pwd -W 2>/dev/null || pwd; }); }
# Git Bash on Windows rewrites arguments that look like paths (/restore/...); Docker needs them as they are.
export MSYS_NO_PATHCONV=1
HERE=$(abs_dir "$(dirname -- "$0")")
COMPOSE_FILE="$HERE/compose.yaml"
REPO_ROOT=$(abs_dir "$HERE/../..")
MIGRATION_DIR="$REPO_ROOT/apps/server/src/main/resources/db/migration"
# enter_repo: Docker Compose builds from the checkout's root without asking for more file access
# only when it runs there. Scripts call it once they have read their arguments.
enter_repo() { cd "$REPO_ROOT" || exit 1; }

step() { printf '== %s\n' "$*"; }
die() {
	printf '%s\n' "$*" >&2
	exit 1
}

compose() { docker compose -f "$COMPOSE_FILE" "$@"; }

# load_model: reads the resolved compose model into MODEL. It holds the database password, so it
# is only read here and never printed. Every script calls it first.
load_model() {
	MODEL=$(compose --profile backup config 2>/dev/null) ||
		die "docker compose config failed: is Docker running, and is ZM_BATON_DB_PASSWORD set in deploy/compose/.env?"
}

project_name() { printf '%s\n' "$MODEL" | sed -n 's/^name: *//p' | head -n 1; }

# The folder on this PC that the backup service writes to.
backup_dir() {
	printf '%s\n' "$MODEL" | awk '
		/^  backup:/ { in_backup = 1; next }
		/^  [a-z]/ { in_backup = 0 }
		in_backup && /source: / { sub(/^ *source: */, ""); source = $0 }
		in_backup && /target: \/backups$/ { print source; exit }'
}

running_services() { compose --profile app --profile backup ps --status running --services 2>/dev/null; }

is_running() { running_services | grep -qx "$1"; }

# ops CMD... : one command of ops/zmb-backup.sh in a fresh backup container. OPS_MOUNT, when
# set, is one extra -v value (host:container[:ro]).
ops() {
	if [ -n "${OPS_MOUNT:-}" ]; then
		compose --profile backup run --rm --no-deps -T -v "$OPS_MOUNT" backup "$@"
	else
		compose --profile backup run --rm --no-deps -T backup "$@"
	fi
}

assert_database_running() {
	is_running postgres || die "the database is not running. Start it first: docker compose -f '$COMPOSE_FILE' up -d postgres"
}

# new_backup KIND: prints the new backup's full path on this PC.
new_backup() {
	out=$(ops once "$1") || die "the backup failed"
	name=$(printf '%s\n' "$out" | tail -n 1)
	printf '%s' "$name" | grep -Eq '^zm-baton-[a-z-]+-[0-9]{8}T[0-9]{6}Z\.dump$' || die "the backup gave no file name (got '$name')"
	printf '%s/%s\n' "$(backup_dir)" "$name"
}

# Migration versions in this checkout, one per line, sorted by number.
local_migrations() {
	ls -1 "$MIGRATION_DIR" | sed -n 's/^V\([0-9][0-9]*\)__.*\.sql$/\1/p' | sort -n
}

# Migration versions the database has applied, one per line.
applied_migrations() { ops versions || die "could not read the database's migrations"; }

# in_list WORD LIST: true when WORD is a line of LIST.
in_list() { printf '%s\n' "$2" | grep -qx "$1"; }

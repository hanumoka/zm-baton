#!/bin/sh
# Replaces the zm-baton database with a backup file (FN-1403). The same steps as restore.ps1:
# 1. refuses unless --confirm-project names this compose project;
# 2. refuses unless the database runs and the server and the daily backup are stopped;
# 3. checks the file (pg_restore can read it; it matches its .sha256 file);
# 4. backs up the current database as zm-baton-before-restore-<UTC time>.dump;
# 5. restores into a new database and swaps it in only when that worked;
# 6. writes the audit row backup.restored and compares migration versions with this checkout.
# Usage:
#   docker compose -f deploy/compose/compose.yaml stop server backup
#   sh deploy/compose/restore.sh --file /backups/zm-baton-daily-20261014T030000Z.dump --confirm-project zm-baton-dev
#   docker compose -f deploy/compose/compose.yaml --profile app up -d
. "$(dirname -- "$0")/ops/host.sh"

file=
confirm=
while [ $# -gt 0 ]; do
	case "$1" in
	--file) file=${2:-}; shift 2 || die "use: --file FILE" ;;
	--confirm-project) confirm=${2:-}; shift 2 || die "use: --confirm-project NAME" ;;
	*) die "use: restore.sh --file <backup file> --confirm-project <compose project name>" ;;
	esac
done
[ -n "$file" ] || die "use: restore.sh --file <backup file> --confirm-project <compose project name>"
[ -f "$file" ] || die "no backup file $file"
folder=$(abs_dir "$(dirname -- "$file")") || die "cannot read the folder of $file"
leaf=$(basename -- "$file")

enter_repo
load_model
project=$(project_name)
step "compose project: $project"
[ "$confirm" = "$project" ] ||
	die "this replaces the database of the compose project '$project'. Run again with --confirm-project $project to confirm"
assert_database_running
if is_running server || is_running backup; then
	die "stop the server and the daily backup first: docker compose -f '$COMPOSE_FILE' stop server backup"
fi

OPS_MOUNT="$folder:/restore:ro"
step "checking $folder/$leaf"
ops check "/restore/$leaf" >/dev/null || die "the file is not a backup that can be restored"

step "backing up the current database first"
OPS_MOUNT=
before=$(new_backup before-restore) || exit 1
step "current database saved as $before"

step "restoring $leaf"
OPS_MOUNT="$folder:/restore:ro"
ops restore "/restore/$leaf" >/dev/null || die "the restore failed; the lines above say whether the database was replaced"
OPS_MOUNT=

applied=$(applied_migrations) || exit 1
ours=$(local_migrations)
step "restored. Migration versions in the database: $(printf '%s' "$applied" | tr '\n' ' ')"
for v in $applied; do
	in_list "$v" "$ours" ||
		printf 'warning: this backup has migration %s, which this checkout does not know. The server starts, but code older than the backup may fail on its tables.\n' "$v" >&2
done
for v in $ours; do
	in_list "$v" "$applied" || step "the server applies migration $v when it starts"
done
step "start the server again: docker compose -f '$COMPOSE_FILE' --profile app up -d"
step "to undo this restore: restore.sh --file '$before' --confirm-project $project"

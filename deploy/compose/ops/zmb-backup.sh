#!/bin/sh
# Backups of the zm-baton database (FN-1403), run inside the compose service `backup`
# (image postgres:17, so pg_dump and pg_restore match the server). Compose starts `loop`; the
# scripts on the PC (backup.ps1, restore.ps1, update.ps1 and their .sh) run the other commands with
# `docker compose run --rm --no-deps -T backup <command>`.
#
#   loop          The daily backup. Every 10 minutes: if the newest daily backup is older than
#                 ZM_BATON_BACKUP_EVERY_HOURS (24), take one, then keep only the newest
#                 ZM_BATON_BACKUP_KEEP (14; 0 keeps all) daily backups. ZM_BATON_BACKUP=off idles.
#   once KIND     One backup now. KIND is manual, before-restore, before-update or daily.
#                 Prints the new file's name on standard output.
#   check FILE    Shows whether FILE is a readable backup and matches its .sha256 file.
#   restore FILE  Replaces the database with FILE: restores into a new database first and swaps
#                 it in only when that worked. The server must be stopped (restore.ps1 checks).
#   versions      The database's applied migration versions, one per line.
#   live-runs     How many runs have not ended.
#
# The connection comes from PGHOST, PGPORT, PGUSER, PGPASSWORD and PGDATABASE (compose sets them).
# Backups are /backups/zm-baton-<kind>-<UTC time>.dump with a .sha256 file beside each. Only the
# daily rotation deletes files, and only files named zm-baton-daily-*.dump and their .sha256.
set -u

DIR=/backups
DAILY_NAME='^zm-baton-daily-[0-9]{8}T[0-9]{6}Z\.dump$'

log() { printf '%s zm-baton-backup: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2; }
fail() { log "$*"; exit 1; }
is_count() { case "$1" in '' | *[!0-9]*) return 1 ;; *) return 0 ;; esac; }

# once KIND: dump to a hidden partial file, check that pg_restore can read it, write its
# checksum, then give both their final names. Nothing half-written ever carries a backup name.
once() {
	kind=$1
	case "$kind" in daily | manual | before-restore | before-update) ;; *) fail "unknown backup kind '$kind'" ;; esac
	mkdir -p "$DIR" || fail "cannot create $DIR"
	tries=0
	while :; do
		name="zm-baton-$kind-$(date -u +%Y%m%dT%H%M%SZ).dump"
		[ -e "$DIR/$name" ] || break
		tries=$((tries + 1))
		[ "$tries" -lt 5 ] || fail "$DIR/$name already exists"
		sleep 1
	done
	tmp="$DIR/.$name.partial"
	if ! pg_dump --format=custom --file="$tmp"; then
		rm -f "$tmp"
		fail "pg_dump failed; no backup was written"
	fi
	if ! pg_restore --list "$tmp" >/dev/null; then
		rm -f "$tmp"
		fail "the new backup could not be read back; it was removed"
	fi
	sum=$(sha256sum "$tmp" | cut -d' ' -f1) || fail "sha256sum failed"
	mv "$tmp" "$DIR/$name" || fail "cannot name the backup $name"
	printf '%s  %s\n' "$sum" "$name" >"$DIR/$name.sha256" || log "could not write $name.sha256"
	log "took $name ($(wc -c <"$DIR/$name") bytes)"
	echo "$name"
}

# prune KEEP: deletes daily backups beyond the newest KEEP. Names sort by time.
prune() {
	keep=$1
	[ "$keep" -eq 0 ] && return 0
	ls -1 "$DIR" | grep -E "$DAILY_NAME" | sort -r | tail -n +$((keep + 1)) | while read -r old; do
		rm -f "$DIR/$old" "$DIR/$old.sha256" && log "removed the old daily backup $old"
	done
}

# due HOURS: true when no daily backup is younger than HOURS.
due() {
	recent=$(find "$DIR" -maxdepth 1 -type f -name 'zm-baton-daily-*.dump' -mmin -$(($1 * 60)) 2>/dev/null | head -n 1)
	[ -z "$recent" ]
}

idle() {
	while :; do
		sleep 3600 &
		wait $!
	done
}

loop() {
	trap 'exit 0' TERM INT
	keep=${ZM_BATON_BACKUP_KEEP:-14}
	every=${ZM_BATON_BACKUP_EVERY_HOURS:-24}
	if ! is_count "$keep" || ! is_count "$every" || [ "$every" -eq 0 ]; then
		log "ZM_BATON_BACKUP_KEEP must be 0 or more and ZM_BATON_BACKUP_EVERY_HOURS 1 or more (now '$keep', '$every'); daily backup is off"
		idle
	fi
	case "${ZM_BATON_BACKUP:-on}" in
	on | true | yes | 1) ;;
	off | false | no | 0)
		log "daily backup is off (ZM_BATON_BACKUP=${ZM_BATON_BACKUP})"
		idle
		;;
	*)
		log "ZM_BATON_BACKUP must be on or off, not '${ZM_BATON_BACKUP}'; daily backup is off"
		idle
		;;
	esac
	if [ "$keep" -eq 0 ]; then kept="every daily backup (ZM_BATON_BACKUP_KEEP=0)"; else kept="the newest $keep daily backups"; fi
	log "daily backup is on: every $every hours, keeping $kept in $DIR"
	# A backup cut short by a stop leaves its hidden partial file; only those of ours are removed.
	find "$DIR" -maxdepth 1 -type f -name '.zm-baton-*.dump.partial' -mmin +60 -exec rm -f {} + 2>/dev/null
	while :; do
		if due "$every"; then
			# A subshell: a failed backup ends only it, not the loop.
			if (once daily) >/dev/null; then
				prune "$keep"
			else
				log "the daily backup failed; trying again in 10 minutes"
			fi
		fi
		sleep 600 &
		wait $!
	done
}

check() {
	file=$1
	[ -f "$file" ] || fail "no backup file $file"
	if [ -f "$file.sha256" ]; then
		want=$(cut -d' ' -f1 <"$file.sha256")
		got=$(sha256sum "$file" | cut -d' ' -f1)
		[ "$want" = "$got" ] || fail "$file does not match $file.sha256: the file changed or is damaged"
		log "checksum matches $file.sha256"
	else
		log "no $file.sha256 beside it; checking the contents only"
	fi
	entries=$(pg_restore --list "$file" 2>/dev/null | grep -c -v '^;') || fail "$file is not a backup pg_restore can read"
	log "readable: $entries entries"
}

# Notices such as "does not exist, skipping" are not shown; warnings and errors are.
sql() { PGOPTIONS="${PGOPTIONS:-} -c client_min_messages=warning" psql -X -q -At -v ON_ERROR_STOP=1 "$@"; }

# has_table NAME: true or false, and fails (exit 1) when the database cannot be asked at all.
has_table() {
	exists=$(sql -c "select to_regclass('public.$1') is not null") || fail "cannot read the database"
	[ "$exists" = "t" ]
}

restore() {
	file=$1
	check "$file"
	db=${PGDATABASE:?PGDATABASE is not set}
	tmpdb="${db}_restoring"
	log "restoring into a new database $tmpdb first; $db stays as it is until that works"
	sql -d postgres -c "drop database if exists \"$tmpdb\" with (force)" -c "create database \"$tmpdb\"" ||
		fail "could not create $tmpdb; $db was not touched"
	if ! pg_restore --no-owner --exit-on-error --dbname="$tmpdb" "$file"; then
		sql -d postgres -c "drop database if exists \"$tmpdb\" with (force)"
		fail "the restore failed; $db was not touched"
	fi
	sql -d postgres -c "drop database \"$db\" with (force)" -c "alter database \"$tmpdb\" rename to \"$db\"" ||
		fail "could not swap $tmpdb in for $db; the restored copy is in $tmpdb"
	if has_table audit_log; then
		# An id like the server's (aud_ and a UUIDv7) for the audit row that says what was restored.
		sum=$(sha256sum "$file" | cut -d' ' -f1)
		sql -v file="$(basename "$file")" -v sum="$sum" <<'SQL' || log "restored, but the audit row could not be written"
insert into audit_log (id, actor_kind, actor_id, on_behalf_of_user_id, action, details)
values (
  'aud_' || encode(set_bit(set_bit(overlay(uuid_send(gen_random_uuid())
      placing substring(int8send((extract(epoch from clock_timestamp()) * 1000)::bigint) from 3) from 1 for 6),
      52, 1), 53, 1), 'hex')::uuid,
  'system', 'restore', 'usr_owner', 'backup.restored',
  jsonb_build_object('file', :'file', 'sha256', :'sum'));
SQL
	fi
	log "restored $db from $(basename "$file")"
}

versions() {
	has_table flyway_schema_history || return 0
	sql -c "select version from flyway_schema_history where success and version is not null order by installed_rank" ||
		fail "cannot read the migration history"
}

live_runs() {
	if has_table runs; then
		sql -c "select count(*) from runs where state <> 'ended'" || fail "cannot count the runs"
	else
		echo 0
	fi
}

cmd=${1:-}
[ $# -gt 0 ] && shift
case "$cmd" in
loop) loop ;;
once) [ $# -eq 1 ] || fail "use: once KIND" && once "$1" ;;
check) [ $# -eq 1 ] || fail "use: check FILE" && check "$1" ;;
restore) [ $# -eq 1 ] || fail "use: restore FILE" && restore "$1" ;;
versions) versions ;;
live-runs) live_runs ;;
*) fail "use: loop | once KIND | check FILE | restore FILE | versions | live-runs" ;;
esac

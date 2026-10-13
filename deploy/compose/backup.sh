#!/bin/sh
# Takes one backup of the zm-baton database now (FN-1403). The same steps as backup.ps1:
# 1. checks that the database container of this compose project is running;
# 2. runs pg_dump in a one-off container of the `backup` service into the backup folder
#    (ZM_BATON_BACKUP_DIR, by default zm-baton-backups next to this checkout) as
#    zm-baton-manual-<UTC time>.dump with a .sha256 file; the daily rotation never deletes it;
# 3. prints the file's full path.
# Reading only: the database is not changed and the server keeps running.
# Usage: sh deploy/compose/backup.sh
. "$(dirname -- "$0")/ops/host.sh"

enter_repo
load_model
step "compose project: $(project_name)"
assert_database_running
file=$(new_backup manual) || exit 1
step "backup written: $file"

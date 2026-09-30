#!/bin/sh
set -eu

umask 077

database=${DUELSCAPE_DATABASE:-/var/lib/duelscape/ranked.db}
backup_dir=${DUELSCAPE_BACKUP_DIR:-/var/backups/duelscape}
retention_days=${DUELSCAPE_BACKUP_RETENTION_DAYS:-14}

case $retention_days in
	''|*[!0-9]*)
		echo "DUELSCAPE_BACKUP_RETENTION_DAYS must be a non-negative integer" >&2
		exit 2
		;;
esac

if [ ! -f "$database" ]; then
	exit 0
fi

snapshot=
compressed=
validation=

cleanup() {
	status=$?
	trap - EXIT HUP INT TERM
	for temporary in "$snapshot" "$compressed" "$validation"; do
		[ -z "$temporary" ] || rm -f -- "$temporary" || true
	done
	exit "$status"
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM

install -d -m 0700 "$backup_dir"
timestamp=$(date -u +%Y%m%dT%H%M%SZ)
snapshot=$(mktemp "$backup_dir/.ranked-snapshot.XXXXXX")
compressed=$(mktemp "$backup_dir/.ranked-compressed.XXXXXX")
validation=$(mktemp "$backup_dir/.ranked-validation.XXXXXX")
destination="$backup_dir/ranked-$timestamp-$$.db.gz"

sqlite3 "$database" ".timeout 5000" ".backup '$snapshot'"
gzip -c "$snapshot" > "$compressed"
gzip -t "$compressed"
gzip -dc "$compressed" > "$validation"

if [ "$(sqlite3 "$validation" 'PRAGMA integrity_check;')" != "ok" ]; then
	echo "Backup SQLite integrity check failed" >&2
	exit 1
fi

chmod 0600 "$compressed"
mv "$compressed" "$destination"
find "$backup_dir" -type f -name 'ranked-*.db.gz' -mtime "+$retention_days" -delete

#!/bin/sh
set -eu

umask 077

if [ "$#" -ne 1 ]; then
	echo "Usage: $0 BACKUP.db.gz" >&2
	exit 2
fi

if [ "$(id -u)" -ne 0 ]; then
	echo "Restore must be run as root" >&2
	exit 1
fi

backup=$1
database=${DUELSCAPE_DATABASE:-/var/lib/duelscape/ranked.db}
service=${DUELSCAPE_SERVICE:-duelscape-backend.service}

if [ ! -f "$backup" ] || [ ! -r "$backup" ]; then
	echo "Backup is not a readable regular file: $backup" >&2
	exit 2
fi

case $database in
	/*) ;;
	*)
		echo "DUELSCAPE_DATABASE must be an absolute path" >&2
		exit 2
		;;
esac

database_dir=${database%/*}
[ -n "$database_dir" ] || database_dir=/
if [ ! -d "$database_dir" ]; then
	echo "Database directory does not exist: $database_dir" >&2
	exit 1
fi

staged=$(mktemp "$database_dir/.ranked-restore.XXXXXX")
preserved=
service_stopped=0
replacement_started=0
restore_complete=0

cleanup() {
	status=$?
	rollback_failed=0
	trap - EXIT HUP INT TERM
	rm -f -- "$staged" || true

	if [ "$service_stopped" -eq 1 ] && [ "$restore_complete" -eq 0 ]; then
		if [ "$replacement_started" -eq 1 ]; then
			systemctl stop "$service" >/dev/null 2>&1 || true
			rm -f -- "$database" "$database-wal" "$database-shm" "$database-journal" || rollback_failed=1
			if [ -n "$preserved" ]; then
				for suffix in '' -wal -shm -journal; do
					name=$(basename "$database$suffix")
					if [ -f "$preserved/$name" ]; then
						cp -p "$preserved/$name" "$database$suffix" || rollback_failed=1
					fi
				done
			fi
		fi
		if [ "$rollback_failed" -ne 0 ]; then
			echo "WARNING: failed to restore one or more preserved database files from $preserved" >&2
		fi
		if ! systemctl start "$service"; then
			echo "WARNING: failed to restart $service after restore failure" >&2
		fi
	fi

	exit "$status"
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM

gzip -t "$backup"
gzip -dc "$backup" > "$staged"
if [ "$(sqlite3 "$staged" 'PRAGMA integrity_check;')" != "ok" ]; then
	echo "Backup SQLite integrity check failed" >&2
	exit 1
fi

chown duelscape:duelscape "$staged"
chmod 0600 "$staged"

systemctl stop "$service"
service_stopped=1

timestamp=$(date -u +%Y%m%dT%H%M%SZ)
preserved="$database_dir/pre-restore-$timestamp-$$"
install -d -m 0700 -o root -g root "$preserved"
for suffix in '' -wal -shm -journal; do
	if [ -f "$database$suffix" ]; then
		cp -p "$database$suffix" "$preserved/$(basename "$database$suffix")"
	fi
done

replacement_started=1
rm -f -- "$database-wal" "$database-shm" "$database-journal"
mv -f "$staged" "$database"

systemctl start "$service"

healthy=0
attempt=0
while [ "$attempt" -lt 30 ]; do
	if systemctl is-active --quiet "$service" && \
		curl --fail --silent --show-error --max-time 2 http://127.0.0.1:8787/healthz >/dev/null; then
		healthy=1
		break
	fi
	attempt=$((attempt + 1))
	sleep 1
done

if [ "$healthy" -ne 1 ]; then
	echo "Restored backend failed its health check; rolling back" >&2
	exit 1
fi

restore_complete=1
echo "Restore complete; previous database files are preserved in $preserved"

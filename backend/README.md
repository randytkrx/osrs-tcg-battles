# Duelscape Backend

The backend is a Java 21 service that reuses the deterministic engine from the plugin source tree.
It is a separate Gradle project so backend dependencies do not enter the RuneLite Plugin Hub build.

Build and test from the repository root:

```powershell
.\gradlew.bat -p backend clean test jar --no-daemon
```

Run locally:

```powershell
java -jar backend\build\libs\duelscape-backend.jar
```

The service binds to `127.0.0.1:8787` by default. Override this with
`DUELSCAPE_BIND_HOST` and `DUELSCAPE_PORT`. Ranked accounts, ratings, and match history are stored
in SQLite at `/var/lib/duelscape/ranked.db`; override that path with `DUELSCAPE_DATABASE`.
Set `DUELSCAPE_RANKED_AUDIENCE` to the exact public `wss://` endpoint clients configure; it defaults
to `wss://play.deargod.live/v1/ws` and is included in every signed ranked-authentication challenge.
`GET /healthz` reports the backend protocol and game ruleset versions.

The protocol supports private lobbies, casual matchmaking, reconnects, and device-key-authenticated
ranked matchmaking. IGN registration is trust-on-first-use because no public Jagex character OAuth
API is available. The service validates every command through the authoritative game engine and
never sends an opponent's hidden cards.

## Deployment prerequisites

The files in `deploy/` target a Linux host running systemd. Install Java 21, Caddy, `sqlite3`,
`gzip`, `curl`, and the standard `coreutils`/`findutils` tools. The restore command must be run as
root because it controls the service and installs files owned by the dedicated `duelscape` user.

Create the system user, install the JAR and scripts under `/opt/duelscape`, and install the unit,
timer, tmpfiles, and Caddy configuration files in their corresponding system locations. Scripts
must be owned by root and not writable by `duelscape`; install them with mode `0755` (for example,
`sudo install -o root -g root -m 0755 deploy/*.sh /opt/duelscape/`). Apply the runtime directories
before first start:

```sh
sudo systemd-tmpfiles --create /etc/tmpfiles.d/duelscape.conf
sudo systemctl daemon-reload
sudo systemctl enable --now duelscape-backend.service
sudo systemctl enable --now duelscape-backup.timer duelscape-healthcheck.timer
```

The service expects `/opt/duelscape/duelscape-backend.jar`, while the units invoke
`/opt/duelscape/backup-ranked.sh` and `/opt/duelscape/healthcheck.sh`. Install
`restore-ranked.sh` there for operator-initiated recovery. Review the Caddy hostname before loading
the supplied Caddyfile.

## Backup and restore

The nightly backup uses SQLite's online backup operation, writes gzip output to a temporary file,
validates both the gzip stream and a decompressed database with `PRAGMA integrity_check`, and only
then atomically renames it into `/var/backups/duelscape`. Successful files are mode `0600` and are
retained for 14 days by default. Override the database, backup directory, or retention with
`DUELSCAPE_DATABASE`, `DUELSCAPE_BACKUP_DIR`, and `DUELSCAPE_BACKUP_RETENTION_DAYS` in a systemd
override. Backups on the same host are not disaster recovery; copy completed `.db.gz` files to
separate, access-controlled storage and test restores periodically.

Restore one validated backup with an explicit path:

```sh
sudo /opt/duelscape/restore-ranked.sh /var/backups/duelscape/ranked-YYYYMMDDTHHMMSSZ-PID.db.gz
```

The restore validates the input before stopping the backend, preserves the current database and
SQLite sidecars in a root-only `pre-restore-*` directory beside the database, atomically installs
the replacement, removes stale sidecars, restarts the service, and waits for `/healthz`. If install,
restart, or health checking fails, it restores the preserved files and attempts to restart the old
database. Keep the reported `pre-restore-*` directory until the restored service has been verified,
then remove it manually when it is no longer needed.

## Availability constraints

This deployment is deliberately single-node and non-HA. SQLite, local systemd units, and local
backup scheduling provide no automatic failover, replication, leader election, or zero-downtime
restore. A restore causes an outage, and the health-check timer only reports failure; it does not
repair or fail over the service. Run exactly one backend process against a database file and plan
host-level monitoring, off-host backups, recovery objectives, and maintenance windows accordingly.

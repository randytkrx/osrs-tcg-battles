#!/bin/sh
set -eu

if ! curl --fail --silent --show-error --max-time 5 http://127.0.0.1:8787/healthz >/dev/null; then
	logger -t duelscape-healthcheck "Health check failed"
	exit 1
fi

#!/usr/bin/env bash
set -euo pipefail

# Intentionally not invoked by deployment. Run inspect first and apply only
# after reviewing the count. The backup contains internal record identifiers,
# so it is created with owner-only permissions and never printed.

readonly DEPLOYMENT_DIR="/home/ubuntu/deployment"
readonly DATABASE_NAME="checkon_backend"
readonly TEACHER_ID="01a03bcd-ec08-7193-839b-621b31c25ce3"
readonly LEGACY_SOURCE="AI_DEMO_FIXTURE"
readonly CORRECTED_SOURCE="MANUAL"
readonly EXPECTED_COUNT=24
readonly BACKUP_DIR="${DEPLOYMENT_DIR}/backups/risk-detection"

usage() {
	printf 'Usage: %s inspect | apply | rollback <backup-file>\n' "$0" >&2
	exit 2
}

[[ $# -ge 1 ]] || usage
readonly ACTION="$1"
shift

cd "$DEPLOYMENT_DIR"
if [[ ! -f .env ]]; then
	printf 'Required deployment .env was not found.\n' >&2
	exit 1
fi

set -a
# shellcheck disable=SC1091
source ./.env
set +a
: "${POSTGRES_USER:?POSTGRES_USER must be set in deployment .env}"

psql_backend() {
	docker compose exec -T postgres psql \
		-X -v ON_ERROR_STOP=1 -qAt \
		-U "$POSTGRES_USER" -d "$DATABASE_NAME" "$@"
}

target_count() {
	psql_backend -c "SELECT count(*) FROM learning_records WHERE teacher_id = '${TEACHER_ID}'::uuid AND source_type = '${LEGACY_SOURCE}';"
}

case "$ACTION" in
	inspect)
		count="$(target_count)"
		printf 'Correction target count: %s (expected %s)\n' "$count" "$EXPECTED_COUNT"
		psql_backend -c "SELECT source_type || '=' || count(*) FROM learning_records WHERE teacher_id = '${TEACHER_ID}'::uuid GROUP BY source_type ORDER BY source_type;"
		;;
	apply)
		[[ $# -eq 0 ]] || usage
		count="$(target_count)"
		if [[ "$count" != "$EXPECTED_COUNT" ]]; then
			printf 'Refusing update: target count is %s, expected %s.\n' "$count" "$EXPECTED_COUNT" >&2
			exit 1
		fi

		umask 077
		mkdir -p "$BACKUP_DIR"
		backup_file="${BACKUP_DIR}/ai-demo-fixture-${TEACHER_ID}-$(date -u +%Y%m%dT%H%M%SZ).csv"
		psql_backend -c "COPY (SELECT id, source_type, updated_at FROM learning_records WHERE teacher_id = '${TEACHER_ID}'::uuid AND source_type = '${LEGACY_SOURCE}' ORDER BY id) TO STDOUT WITH (FORMAT csv, HEADER true);" > "$backup_file"
		chmod 600 "$backup_file"
		backup_count="$(( $(wc -l < "$backup_file") - 1 ))"
		if [[ "$backup_count" != "$EXPECTED_COUNT" ]]; then
			printf 'Refusing update: restricted backup has %s rows, expected %s.\n' "$backup_count" "$EXPECTED_COUNT" >&2
			exit 1
		fi

		psql_backend <<SQL
BEGIN;
DO \$correction\$
DECLARE
    locked_count integer;
    updated_count integer;
BEGIN
    PERFORM id
      FROM learning_records
     WHERE teacher_id = '${TEACHER_ID}'::uuid
       AND source_type = '${LEGACY_SOURCE}'
     FOR UPDATE;

    SELECT count(*) INTO locked_count
      FROM learning_records
     WHERE teacher_id = '${TEACHER_ID}'::uuid
       AND source_type = '${LEGACY_SOURCE}';
    IF locked_count <> ${EXPECTED_COUNT} THEN
        RAISE EXCEPTION 'target count changed during correction: %', locked_count;
    END IF;

    UPDATE learning_records
       SET source_type = '${CORRECTED_SOURCE}',
           updated_at = clock_timestamp()
     WHERE teacher_id = '${TEACHER_ID}'::uuid
       AND source_type = '${LEGACY_SOURCE}';
    GET DIAGNOSTICS updated_count = ROW_COUNT;
    IF updated_count <> ${EXPECTED_COUNT} THEN
        RAISE EXCEPTION 'updated row count mismatch: %', updated_count;
    END IF;
END
\$correction\$;
COMMIT;
SQL
		remaining="$(target_count)"
		manual_count="$(psql_backend -c "SELECT count(*) FROM learning_records WHERE teacher_id = '${TEACHER_ID}'::uuid AND source_type = '${CORRECTED_SOURCE}';")"
		printf 'Correction committed. Remaining legacy rows: %s; teacher MANUAL rows: %s.\n' "$remaining" "$manual_count"
		printf 'Restricted rollback backup: %s\n' "$backup_file"
		;;
	rollback)
		[[ $# -eq 1 ]] || usage
		backup_file="$1"
		if [[ ! -f "$backup_file" ]]; then
			printf 'Backup file was not found.\n' >&2
			exit 1
		fi
		if [[ "$(stat -c '%a' "$backup_file")" != "600" ]]; then
			printf 'Refusing rollback: backup permissions must be 600.\n' >&2
			exit 1
		fi

		{
			printf '%s\n' 'BEGIN;' \
				'CREATE TEMP TABLE correction_backup (id uuid, source_type text, updated_at timestamptz);' \
				'COPY correction_backup (id, source_type, updated_at) FROM STDIN WITH (FORMAT csv, HEADER true);'
			cat "$backup_file"
			printf '%s\n' '\.' \
				"DO \$rollback\$" \
				'DECLARE restored_count integer;' \
				'BEGIN' \
				"  IF (SELECT count(*) FROM correction_backup) <> ${EXPECTED_COUNT} THEN" \
				"    RAISE EXCEPTION 'backup row count mismatch';" \
				'  END IF;' \
				"  IF EXISTS (SELECT 1 FROM correction_backup WHERE source_type <> '${LEGACY_SOURCE}') THEN" \
				"    RAISE EXCEPTION 'backup contains an unexpected source';" \
				'  END IF;' \
				'  UPDATE learning_records record' \
				'     SET source_type = backup.source_type,' \
				'         updated_at = backup.updated_at' \
				'    FROM correction_backup backup' \
				'   WHERE record.id = backup.id' \
				"     AND record.teacher_id = '${TEACHER_ID}'::uuid" \
				"     AND record.source_type = '${CORRECTED_SOURCE}';" \
				'  GET DIAGNOSTICS restored_count = ROW_COUNT;' \
				"  IF restored_count <> ${EXPECTED_COUNT} THEN" \
				"    RAISE EXCEPTION 'restored row count mismatch: %', restored_count;" \
				'  END IF;' \
				'END' \
				"\$rollback\$;" \
				'COMMIT;'
		} | psql_backend
		printf 'Rollback committed. Restored legacy rows: %s.\n' "$(target_count)"
		;;
	*)
		usage
		;;
esac

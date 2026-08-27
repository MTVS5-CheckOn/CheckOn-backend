#!/usr/bin/env bash
set -euo pipefail

# Run one stage at a time. The script never deletes or rewrites an existing run.
# AUTH_BEARER_TOKEN is optional for the temporary fixed-auth demo deployment and
# is consumed only as an HTTP header; it is never printed.

readonly DEPLOYMENT_DIR="/home/ubuntu/deployment"
readonly STATE_DIR="${DEPLOYMENT_DIR}/.risk-detection-verification"
readonly BACKEND_BASE_URL="${BACKEND_BASE_URL:?BACKEND_BASE_URL must be set without a trailing slash}"
readonly DATABASE_NAME="checkon_backend"
readonly TEACHER_ID="01a03bcd-ec08-7193-839b-621b31c25ce3"

usage() {
	printf 'Usage: %s preflight <analysis-date> | submit <analysis-date> | wait | frontend\n' "$0" >&2
	exit 2
}

auth_args=()
if [[ -n "${AUTH_BEARER_TOKEN:-}" ]]; then
	auth_args=(-H "Authorization: Bearer ${AUTH_BEARER_TOKEN}")
fi

api() {
	curl --fail-with-body --silent --show-error "${auth_args[@]}" "$@"
}

[[ $# -ge 1 ]] || usage
readonly STAGE="$1"
shift
cd "$DEPLOYMENT_DIR"

if [[ -f .env ]]; then
	set -a
	# shellcheck disable=SC1091
	source ./.env
	set +a
fi

existing_run_count() {
	: "${POSTGRES_USER:?POSTGRES_USER must be set in deployment .env}"
	docker compose exec -T postgres psql \
		-X -v ON_ERROR_STOP=1 -qAt \
		-U "$POSTGRES_USER" -d "$DATABASE_NAME" \
		-c "SELECT count(*) FROM detection_runs WHERE teacher_id = '${TEACHER_ID}'::uuid AND analysis_date = '$1'::date;"
}

case "$STAGE" in
	preflight)
		[[ $# -eq 1 ]] || usage
		analysis_date="$1"
		[[ "$analysis_date" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || usage
		api "${BACKEND_BASE_URL}/actuator/health" >/dev/null
		if [[ "$(existing_run_count "$analysis_date")" != "0" ]]; then
			printf 'Refusing submission: the selected analysisDate already has a run for this teacher.\n' >&2
			exit 1
		fi
		printf 'Preflight passed for analysisDate=%s. No existing run will be changed.\n' "$analysis_date"
		;;
	submit)
		[[ $# -eq 1 ]] || usage
		analysis_date="$1"
		[[ "$analysis_date" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || usage
		umask 077
		mkdir -p "$STATE_DIR"
		response="$(api -X POST "${BACKEND_BASE_URL}/api/v1/detection-runs" \
			-H 'Content-Type: application/json' \
			--data "{\"analysisDate\":\"${analysis_date}\",\"termContext\":\"normal\"}")"
		run_id="$(jq -er '.runId' <<<"$response")"
		if ! jq -e '.created == true' <<<"$response" >/dev/null; then
			printf 'Refusing verification: Backend returned an existing run instead of creating a new one.\n' >&2
			exit 1
		fi
		printf '%s' "$run_id" > "${STATE_DIR}/run-id"
		chmod 600 "${STATE_DIR}/run-id"
		printf 'Detection request accepted. runId=%s\n' "$run_id"
		;;
	wait)
		[[ $# -eq 0 ]] || usage
		run_id="$(<"${STATE_DIR}/run-id")"
		for _ in $(seq 1 180); do
			response="$(api "${BACKEND_BASE_URL}/api/v1/detection-runs/${run_id}")"
			status="$(jq -er '.status' <<<"$response")"
			if [[ "$status" == "SUCCEEDED" ]]; then
				jq '{runId,status,analysisDate,snapshotHash,aiExecutionId,attemptCount,errorCode,stats}' <<<"$response"
				exit 0
			fi
			if [[ "$status" == "FAILED" ]]; then
				jq '{runId,status,analysisDate,attemptCount,errorCode}' <<<"$response" >&2
				exit 1
			fi
			sleep 1
		done
		printf 'Timed out before Backend reached a terminal state.\n' >&2
		exit 1
		;;
	frontend)
		[[ $# -eq 0 ]] || usage
		run_id="$(<"${STATE_DIR}/run-id")"
		latest="$(api "${BACKEND_BASE_URL}/api/v1/detection-runs/latest")"
		jq -e --arg run_id "$run_id" '.runId == $run_id and .status == "SUCCEEDED"' <<<"$latest" >/dev/null
		alerts="$(api "${BACKEND_BASE_URL}/api/v1/engagement/alerts?status=PENDING_REVIEW")"
		matching_alerts="$(jq --arg run_id "$run_id" '[.[] | select(.runId == $run_id)] | length' <<<"$alerts")"
		printf 'Frontend read APIs confirmed latest SUCCEEDED runId=%s; matching pending alerts=%s.\n' "$run_id" "$matching_alerts"
		;;
	*)
		usage
		;;
esac

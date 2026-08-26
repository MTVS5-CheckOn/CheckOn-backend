#!/usr/bin/env bash

set -Eeuo pipefail
umask 077

readonly DEPLOY_DIR="/home/ubuntu/deployment"
readonly ENV_FILE="${DEPLOY_DIR}/.env"
readonly COMPOSE_FILE="${DEPLOY_DIR}/compose.yaml"
readonly LOCK_FILE="/var/lock/checkon-backend-deploy.lock"
readonly IMAGE_PATTERN='^ghcr\.io/mtvs5-checkon/checkon-backend:sha-[0-9a-f]{40}$'

if [[ "$#" -ne 1 ]]; then
  echo "Usage: $0 <backend-image>" >&2
  exit 2
fi

readonly TARGET_IMAGE="$1"

if [[ ! "$TARGET_IMAGE" =~ $IMAGE_PATTERN ]]; then
  echo "Invalid backend image: $TARGET_IMAGE" >&2
  exit 2
fi

for required_command in awk chmod chown cp docker flock grep mktemp mv rm sleep; do
  if ! command -v "$required_command" >/dev/null 2>&1; then
    echo "Required command is missing: $required_command" >&2
    exit 3
  fi
done

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Environment file is missing: $ENV_FILE" >&2
  exit 3
fi

if [[ ! -f "$COMPOSE_FILE" ]]; then
  echo "Compose file is missing: $COMPOSE_FILE" >&2
  exit 3
fi

cd "$DEPLOY_DIR"

exec 9>"$LOCK_FILE"

if ! flock -n 9; then
  echo "Another backend deployment is already running." >&2
  exit 4
fi

image_key_count="$(
  grep -c '^BACKEND_IMAGE=' "$ENV_FILE" || true
)"

if [[ "$image_key_count" -ne 1 ]]; then
  echo "BACKEND_IMAGE must exist exactly once in $ENV_FILE" >&2
  exit 3
fi

current_image_line="$(
  grep '^BACKEND_IMAGE=' "$ENV_FILE"
)"
previous_image="${current_image_line#BACKEND_IMAGE=}"
readonly PREVIOUS_IMAGE="${previous_image%$'\r'}"

if [[ ! "$PREVIOUS_IMAGE" =~ $IMAGE_PATTERN ]]; then
  echo "Invalid previous backend image: $PREVIOUS_IMAGE" >&2
  exit 3
fi

backup_file=""
next_env_file=""
env_updated="false"
deploy_succeeded="false"

wait_for_readiness() {
  local attempt
  local response

  for ((attempt = 1; attempt <= 15; attempt++)); do
    response="$(
      docker compose exec -T web \
        wget -qO- \
        http://back:8080/actuator/health/readiness \
        2>/dev/null ||
        true
    )"

    if [[ "$response" == *'"status":"UP"'* ]]; then
      return 0
    fi

    sleep 2
  done

  return 1
}

finish() {
  local exit_code="$?"

  trap - EXIT
  set +e

  if [[ "$deploy_succeeded" == "true" ]]; then
    [[ -n "$backup_file" ]] && rm -f "$backup_file"
    [[ -n "$next_env_file" ]] && rm -f "$next_env_file"
    exit 0
  fi

  if [[ "$env_updated" == "true" && -f "$backup_file" ]]; then
    echo "Deployment failed. Restoring previous image: $PREVIOUS_IMAGE" >&2

    cp --preserve=all "$backup_file" "$ENV_FILE"

    docker compose config --quiet

    docker compose up \
      --detach \
      --no-deps \
      --wait \
      --wait-timeout 300 \
      back

    docker compose exec -T web nginx -s reload

    if wait_for_readiness; then
      echo "Rollback completed: $PREVIOUS_IMAGE" >&2
    else
      echo "Rollback readiness verification failed." >&2
    fi
  fi

  [[ -n "$backup_file" ]] && rm -f "$backup_file"
  [[ -n "$next_env_file" ]] && rm -f "$next_env_file"

  exit "$exit_code"
}

trap finish EXIT

echo "Pulling backend image: $TARGET_IMAGE"
docker pull "$TARGET_IMAGE"

backup_file="$(
  mktemp "${DEPLOY_DIR}/.env.deploy-backup.XXXXXX"
)"
cp --preserve=all "$ENV_FILE" "$backup_file"

next_env_file="$(
  mktemp "${DEPLOY_DIR}/.env.deploy-next.XXXXXX"
)"

awk -v target_image="$TARGET_IMAGE" '
  BEGIN {
    replaced = 0
  }

  /^BACKEND_IMAGE=/ {
    print "BACKEND_IMAGE=" target_image
    replaced++
    next
  }

  {
    print
  }

  END {
    if (replaced != 1) {
      exit 42
    }
  }
' "$ENV_FILE" > "$next_env_file"

chmod --reference="$ENV_FILE" "$next_env_file"
chown --reference="$ENV_FILE" "$next_env_file"
mv "$next_env_file" "$ENV_FILE"
next_env_file=""
env_updated="true"

docker compose config --quiet

docker compose up \
  --detach \
  --no-deps \
  --wait \
  --wait-timeout 300 \
  back

backend_container_id="$(
  docker compose ps -q back
)"

if [[ -z "$backend_container_id" ]]; then
  echo "Backend container was not created." >&2
  exit 5
fi

running_image="$(
  docker inspect \
    --format '{{.Config.Image}}' \
    "$backend_container_id"
)"

if [[ "$running_image" != "$TARGET_IMAGE" ]]; then
  echo "Running image does not match target image." >&2
  echo "Expected: $TARGET_IMAGE" >&2
  echo "Actual:   $running_image" >&2
  exit 5
fi

docker compose exec -T web nginx -s reload

if ! wait_for_readiness; then
  echo "Backend readiness verification failed." >&2
  exit 5
fi

deploy_succeeded="true"

echo "Backend deployment completed."
echo "Previous image: $PREVIOUS_IMAGE"
echo "Current image:  $TARGET_IMAGE"

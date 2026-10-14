#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
env_file="${DEPLOY_ENV_FILE:-$project_dir/infra/deploy/.env}"
project_name="${DEPLOY_PROJECT_NAME:-receipt-production}"

usage() {
    cat <<'HELP'
사용법: ./scripts/deploy.sh {build|bootstrap|up|status|logs|down}

먼저 infra/deploy/.env.example을 같은 폴더의 .env로 복사하고 값을 입력하세요.
build → bootstrap(새 DB에서 한 번) → up 순서로 실행합니다.
down은 DB/Redis 볼륨을 삭제하지 않습니다.
HELP
}

if [[ $# -ne 1 ]]; then
    usage
    exit 1
fi
case "$1" in
    build|bootstrap|up|status|logs|down) ;;
    *) usage; exit 1 ;;
esac

if [[ ! -f "$env_file" ]]; then
    echo "환경 설정 파일이 없습니다: $env_file" >&2
    usage
    exit 1
fi

compose=(docker compose --project-name "$project_name" --env-file "$env_file"
    --file "$project_dir/docker-compose.deploy.yml")

# 값 전체를 출력하지 않고 필수 설정과 Compose 문법만 확인합니다.
"${compose[@]}" config --quiet

case "$1" in
    build)
        "${compose[@]}" build app
        ;;
    bootstrap)
        read -r -p '첫 관리자 로그인 ID: ' admin_login
        if [[ -z "$admin_login" ]]; then
            echo '로그인 ID를 입력하세요.' >&2
            exit 1
        fi
        read -r -s -p '첫 관리자 비밀번호 (12자 이상, UTF-8 72바이트 이하): ' admin_password
        printf '\n'
        read -r -s -p '비밀번호 확인: ' password_confirmation
        printf '\n'
        if [[ "$admin_password" != "$password_confirmation" ]]; then
            echo '비밀번호가 일치하지 않습니다.' >&2
            exit 1
        fi

        "${compose[@]}" up -d --wait mysql redis
        # stdin으로 전달하여 비밀번호를 명령 인자나 영구 파일에 기록하지 않습니다.
        # 파일은 컨테이너 사용자 소유/chmod 600이며 종료 시 컨테이너와 함께 삭제됩니다.
        printf '%s' "$admin_password" | "${compose[@]}" run --rm --no-deps -T \
            --entrypoint sh \
            -e SPRING_MAIN_WEB_APPLICATION_TYPE=none \
            -e RECEIPT_WORKER_ENABLED=false \
            -e RECEIPT_BOOTSTRAP_ENABLED=true \
            -e "RECEIPT_BOOTSTRAP_LOGIN_ID=$admin_login" \
            -e RECEIPT_BOOTSTRAP_PASSWORD_FILE=/tmp/admin-password \
            app -c 'umask 077; cat > /tmp/admin-password; java -jar /app/app.jar'
        unset admin_password password_confirmation
        ;;
    up)
        "${compose[@]}" up -d --wait --wait-timeout 180
        ;;
    status)
        "${compose[@]}" ps
        ;;
    logs)
        "${compose[@]}" logs --tail 100 -f app
        ;;
    down)
        "${compose[@]}" down
        ;;
esac

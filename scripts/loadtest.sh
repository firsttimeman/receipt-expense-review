#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"
compose=(docker compose --env-file /dev/null --project-name receipt-loadtest --file infra/loadtest/compose.yml)
base_url="http://127.0.0.1:${LOADTEST_PORT:-19880}"

query() {
    "${compose[@]}" exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -u "$MYSQL_USER" -D "$MYSQL_DATABASE" --batch --skip-column-names' <<< "$1"
}

case "${1:-help}" in
    up)
        python3 load/generate.py
        "${compose[@]}" build app
        "${compose[@]}" up -d --wait --wait-timeout 180 mysql redis s3mock influxdb grafana
        table_count="$(query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='employees';")"
        employee_count=0
        if [[ "$table_count" == 1 ]]; then
            employee_count="$(query 'SELECT COUNT(*) FROM employees;')"
        fi
        if [[ "$employee_count" == 0 ]]; then
            # 기존 관리자 준비 기능을 사용합니다. 비밀번호를 명령행 인자로 전달하지 않습니다.
            python3 -c 'import json; print(json.load(open("load/generated/admin.json"))["password"], end="")' |
                "${compose[@]}" run --rm --no-deps -T --entrypoint sh \
                    -e SPRING_MAIN_WEB_APPLICATION_TYPE=none \
                    -e RECEIPT_WORKER_ENABLED=false \
                    -e RECEIPT_BOOTSTRAP_ENABLED=true \
                    -e RECEIPT_BOOTSTRAP_LOGIN_ID=loadtest-admin \
                    -e RECEIPT_BOOTSTRAP_PASSWORD_FILE=/tmp/admin-password \
                    app -c 'umask 077; cat > /tmp/admin-password; java -jar /app/app.jar'
        fi
        "${compose[@]}" up -d --wait --wait-timeout 180 app
        "${compose[@]}" exec -T app curl --fail --silent --output /dev/null \
            --retry 15 --retry-connrefused --retry-delay 1 http://s3mock:9090/receipt-loadtest
        printf '테스트 서버: %s (Fake 추출 지연 %s, 동시 처리 %s개)\n' "$base_url" "${LOADTEST_DELAY:-2s}" "${LOADTEST_CONCURRENCY:-4}"
        printf 'Grafana: http://localhost:%s/d/receipt-loadtest/receipt-loadtest\n' "${LOADTEST_GRAFANA_PORT:-13000}"
        ;;
    prepare)
        python3 load/prepare-users.py --base-url "$base_url" --count "${LOADTEST_USERS:-10}"
        ;;
    smoke)
        RATE=1 TIME_UNIT=5s DURATION=15s PRE_VUS=2 MAX_VUS=8 COMPLETION_TIMEOUT_SECONDS=30 \
            python3 load/run.py --base-url "$base_url"
        ;;
    run)
        python3 load/run.py --base-url "$base_url"
        ;;
    duplicates)
        python3 load/run-duplicates.py --base-url "$base_url"
        ;;
    reads)
        shift
        python3 load/run-reads.py "$@"
        ;;
    status)
        "${compose[@]}" ps
        ;;
    stats)
        query 'SELECT status, COUNT(*) AS jobs FROM receipt_extraction_jobs GROUP BY status;'
        "${compose[@]}" stats --no-stream
        ;;
    down)
        # 테스트 전용 컨테이너만 종료합니다. DB와 이미지 볼륨은 유지합니다.
        "${compose[@]}" down
        ;;
    help|--help|-h)
        cat <<'HELP'
사용법: ./scripts/loadtest.sh {up|prepare|smoke|run|duplicates|reads|status|stats|down}

up       독립 MySQL/Redis/S3Mock, Fake 추출 서버, Grafana/InfluxDB 준비
prepare  테스트 직원 10명 발급 (LOADTEST_USERS로 조절)
smoke    5초마다 1건씩 15초간 신규 제출 및 추출 완료 검증
run      기본 초당 1건, 60초 실행. RATE/TIME_UNIT/DURATION으로 조절
duplicates 동일 이미지 10·50개 동시 제출 및 완료 후 재전송, 각 3회 검증
reads    직원 조회 재사용 전후의 상세 API 측정 (--phase before 또는 after 필수)
status   테스트 컨테이너 상태
stats    전체 작업 상태와 컨테이너 자원 사용량
down     테스트 컨테이너 종료 (데이터 유지)

예: LOADTEST_DELAY=2s LOADTEST_CONCURRENCY=4 ./scripts/loadtest.sh up
예: RATE=3 DURATION=60s MAX_VUS=128 ./scripts/loadtest.sh run
안내: load/USAGE.txt
HELP
        ;;
    *) printf '알 수 없는 명령: %s\n' "$1" >&2; exit 2 ;;
esac

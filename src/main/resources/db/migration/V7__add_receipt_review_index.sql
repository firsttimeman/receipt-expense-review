-- 검수 목록의 ID와 전체 건수는 상태·기간·소유자 조건을 인덱스 컬럼만으로 판단할 수 있습니다.
-- 단일 상태 조건에서는 created_at DESC, id DESC를 같은 인덱스의 역순으로 읽을 수 있습니다.
-- 여러 상태를 함께 찾는 목록의 정렬 제거 여부는 실행 계획으로 확인합니다.
CREATE INDEX ix_receipts_review_status_created
    ON receipts (status, created_at, id, owner_employee_id);

-- US-31 Xác thực hai lớp: 10 mã dự phòng dùng một lần mỗi người dùng (class diagram RecoveryCode).

create table recovery_code
(
    id              uuid primary key     default gen_random_uuid(),
    organization_id uuid        not null references organization (id),
    user_id         uuid        not null references app_user (id) on delete cascade,
    code_hash       char(64)    not null,  -- SHA-256 hex của mã đã chuẩn hóa
    created_at      timestamptz not null default now(),
    used_at         timestamptz
);
create index ix_recovery_code_user on recovery_code (user_id) where used_at is null;
select camhub_enable_tenant_rls('recovery_code');

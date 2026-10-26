-- US-21 Thời gian lưu trữ, giữ lại phục vụ điều tra, tự xóa (INV-06, INV-07, RULE-02).

create table retention_policy
(
    organization_id        uuid primary key references organization (id),
    -- Mặc định 30 ngày cho tới khi chốt OQ-04
    default_retention_days int         not null default 30 check (default_retention_days between 1 and 3650),
    updated_by             uuid,
    updated_at             timestamptz not null default now()
);
select camhub_enable_tenant_rls('retention_policy');

create table camera_retention_override
(
    camera_id       uuid primary key references camera (id) on delete cascade,
    organization_id uuid        not null references organization (id),
    retention_days  int         not null check (retention_days between 1 and 3650),
    updated_by      uuid,
    updated_at      timestamptz not null default now()
);
select camhub_enable_tenant_rls('camera_retention_override');

create table evidence_hold
(
    id              uuid primary key      default gen_random_uuid(),
    organization_id uuid         not null references organization (id),
    camera_id       uuid         not null references camera (id),  -- không cascade: còn giữ thì không xóa được camera
    start_at        timestamptz  not null,
    end_at          timestamptz  not null,
    reason          varchar(500) not null,
    created_by      uuid         not null,
    created_at      timestamptz  not null default now(),
    released_at     timestamptz,
    released_by     uuid,
    check (end_at > start_at)
);
create index ix_evidence_hold_camera on evidence_hold (camera_id, start_at) where released_at is null;
select camhub_enable_tenant_rls('evidence_hold');

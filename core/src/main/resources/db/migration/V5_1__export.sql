-- US-20 Xuất clip: ExportJob QUEUED → PROCESSING → READY → EXPIRED (hoặc FAILED). Tệp MP4 nằm trong MinIO.

create table export_job
(
    id              uuid primary key      default gen_random_uuid(),
    organization_id uuid         not null references organization (id),
    camera_id       uuid         not null references camera (id) on delete cascade,
    -- Lưu tên lúc xuất: camera có thể đổi tên/xóa sau đó nhưng danh sách clip vẫn đọc được
    camera_name     varchar(200) not null,
    start_at        timestamptz  not null,
    end_at          timestamptz  not null,
    status          varchar(16)  not null default 'QUEUED'
        check (status in ('QUEUED', 'PROCESSING', 'READY', 'FAILED', 'EXPIRED')),
    requested_by    uuid         not null,
    requested_at    timestamptz  not null default now(),
    started_at      timestamptz,
    completed_at    timestamptz,
    -- READY + 24 giờ (US-20 AC4)
    expires_at      timestamptz,
    bucket          varchar(100),
    object_key      varchar(300),
    size_bytes      bigint,
    sha256          varchar(64),
    error_code      varchar(40),
    error_message   varchar(500),
    check (end_at > start_at and end_at - start_at <= interval '60 minutes')
);
create index ix_export_job_user on export_job (organization_id, requested_by, requested_at desc);
create index ix_export_job_open on export_job (status, requested_at) where status in ('QUEUED', 'PROCESSING', 'READY');
select camhub_enable_tenant_rls('export_job');

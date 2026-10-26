-- US-27 Cấu hình mẫu: một mẫu gồm chính sách ghi, luồng chính, hình ảnh; áp cho nhiều camera, kết quả từng camera.

create table config_template
(
    id              uuid primary key      default gen_random_uuid(),
    organization_id uuid         not null references organization (id),
    name            varchar(200) not null,
    description     varchar(500),
    -- Phần nào null thì không áp phần đó
    recording       jsonb,
    stream          jsonb,
    image           jsonb,
    created_by      uuid,
    created_at      timestamptz  not null default now(),
    updated_at      timestamptz  not null default now(),
    unique (organization_id, name)
);
select camhub_enable_tenant_rls('config_template');

create table template_application
(
    id              uuid primary key     default gen_random_uuid(),
    organization_id uuid        not null references organization (id),
    template_id     uuid        not null references config_template (id) on delete cascade,
    started_by      uuid        not null,
    started_at      timestamptz not null default now(),
    finished_at     timestamptz
);
select camhub_enable_tenant_rls('template_application');

create table template_application_result
(
    application_id  uuid        not null references template_application (id) on delete cascade,
    organization_id uuid        not null references organization (id),
    camera_id       uuid        not null references camera (id) on delete cascade,
    camera_name     varchar(200) not null,
    -- SUCCESS / PARTIAL (bỏ qua tham số camera không hỗ trợ) / FAILED
    result          varchar(16) not null check (result in ('SUCCESS', 'PARTIAL', 'FAILED')),
    details         jsonb       not null default '[]',
    applied_at      timestamptz not null default now(),
    primary key (application_id, camera_id)
);
select camhub_enable_tenant_rls('template_application_result');

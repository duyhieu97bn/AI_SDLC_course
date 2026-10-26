-- US-34 Cảnh báo mất kết nối, US-35 cảnh báo dung lượng lưu trữ, thông báo web/email (class diagram mục 7).

-- Quy tắc cảnh báo theo Tổ chức: mỗi loại một dòng, tham số trong params
create table alert_rule
(
    id              uuid primary key     default gen_random_uuid(),
    organization_id uuid        not null references organization (id),
    type            varchar(32) not null check (type in ('CAMERA_OFFLINE', 'SITE_OFFLINE', 'STORAGE_THRESHOLD')),
    enabled         boolean     not null default true,
    -- CAMERA_OFFLINE/SITE_OFFLINE: {"offlineDelayMinutes":5}; STORAGE_THRESHOLD: {"warningPercent":85,"criticalPercent":95}
    params          jsonb       not null default '{}',
    -- Kênh gửi: WEB luôn có; EMAIL tới người nhận có email
    channels        text[]      not null default '{WEB,EMAIL}',
    updated_by      uuid,
    updated_at      timestamptz not null default now(),
    unique (organization_id, type)
);
select camhub_enable_tenant_rls('alert_rule');

create table alert
(
    id              uuid primary key      default gen_random_uuid(),
    organization_id uuid         not null references organization (id),
    type            varchar(32)  not null,
    severity        varchar(16)  not null check (severity in ('INFO', 'WARNING', 'CRITICAL')),
    -- NEW → IN_PROGRESS → CLOSED; RECOVERED khi nguồn tự hết lỗi (US-34 AC3)
    status          varchar(16)  not null default 'NEW' check (status in ('NEW', 'IN_PROGRESS', 'RECOVERED', 'CLOSED')),
    -- Khóa nguồn để không tạo trùng cảnh báo đang mở: camera:<id>, site:<id>, storage:<volume>
    source_key      varchar(200) not null,
    camera_id       uuid,
    site_id         uuid,
    title           varchar(300) not null,
    details         jsonb        not null default '{}',
    occurred_at     timestamptz  not null,
    created_at      timestamptz  not null default now(),
    recovered_at    timestamptz,
    assignee_id     uuid,
    closed_at       timestamptz,
    outcome         varchar(32)
);
create unique index ux_alert_open_source on alert (organization_id, source_key) where status in ('NEW', 'IN_PROGRESS');
create index ix_alert_org_time on alert (organization_id, occurred_at desc);
select camhub_enable_tenant_rls('alert');

create table alert_status_change
(
    id              uuid primary key      default gen_random_uuid(),
    organization_id uuid         not null references organization (id),
    alert_id        uuid         not null references alert (id) on delete cascade,
    from_status     varchar(16),
    to_status       varchar(16)  not null,
    actor_id        uuid,
    note            varchar(500),
    changed_at      timestamptz  not null default now()
);
create index ix_alert_status_change on alert_status_change (alert_id, changed_at);
select camhub_enable_tenant_rls('alert_status_change');

-- Thông báo tới từng người dùng; email gửi lại tối đa 3 lần (US-34.2)
create table notification
(
    id              uuid primary key      default gen_random_uuid(),
    organization_id uuid         not null references organization (id),
    user_id         uuid         not null references app_user (id) on delete cascade,
    alert_id        uuid references alert (id) on delete set null,
    channel         varchar(16)  not null check (channel in ('WEB', 'EMAIL')),
    title           varchar(300) not null,
    body            varchar(2000),
    link            varchar(500),
    status          varchar(16)  not null default 'PENDING' check (status in ('PENDING', 'SENT', 'FAILED')),
    attempts        int          not null default 0,
    next_attempt_at timestamptz  not null default now(),
    last_error      varchar(500),
    created_at      timestamptz  not null default now(),
    sent_at         timestamptz,
    read_at         timestamptz
);
create index ix_notification_user on notification (organization_id, user_id, created_at desc) where channel = 'WEB';
create index ix_notification_pending on notification (next_attempt_at) where status = 'PENDING';
select camhub_enable_tenant_rls('notification');

-- Thông báo chờ gửi của mọi Tổ chức (job gửi email chạy ngoài ngữ cảnh Tổ chức)
create function camhub_pending_notification_orgs()
    returns setof uuid
    language sql
    stable
    security definer
    set search_path = public
as
$$
select distinct organization_id from notification where status = 'PENDING' and next_attempt_at <= now()
$$;
revoke all on function camhub_pending_notification_orgs() from public;
grant execute on function camhub_pending_notification_orgs() to ${app_role};

-- US-35: ổ lưu trữ ghi hình dùng chung của triển khai (không thuộc riêng Tổ chức nào, không RLS)
create table storage_volume
(
    name        varchar(100) primary key,
    path        varchar(300) not null,
    total_bytes bigint       not null,
    used_bytes  bigint       not null,
    measured_at timestamptz  not null
);
create table storage_measurement
(
    volume_name varchar(100) not null references storage_volume (name) on delete cascade,
    measured_at timestamptz  not null,
    used_bytes  bigint       not null,
    primary key (volume_name, measured_at)
);

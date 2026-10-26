-- S7: US-36 cảnh báo chuyển động đa kênh, US-37 xử lý và chuyển giao cảnh báo, kênh Telegram/Zalo.

-- Quy tắc chuyển động: phạm vi + lịch + chống spam + kênh gửi (nhiều quy tắc mỗi Tổ chức)
create table motion_rule
(
    id               uuid primary key      default gen_random_uuid(),
    organization_id  uuid         not null references organization (id),
    name             varchar(200) not null,
    enabled          boolean      not null default true,
    scope_type       varchar(16)  not null check (scope_type in ('ORGANIZATION', 'SITE', 'ZONE', 'CAMERA')),
    scope_id         uuid,
    -- [{"days":[1..7], "from":"22:00", "to":"06:00"}] theo giờ địa phương của địa điểm; rỗng = luôn áp dụng
    schedule         jsonb        not null default '[]',
    cooldown_seconds int          not null default 120 check (cooldown_seconds between 0 and 86400),
    channels         text[]       not null default '{WEB}',
    include_snapshot boolean      not null default true,
    created_by       uuid,
    created_at       timestamptz  not null default now(),
    updated_at       timestamptz  not null default now(),
    check ((scope_type = 'ORGANIZATION') = (scope_id is null))
);
select camhub_enable_tenant_rls('motion_rule');

alter table alert
    add column rule_id             uuid references motion_rule (id) on delete set null,
    add column snapshot_bucket     varchar(100),
    add column snapshot_object_key varchar(300),
    add column snapshot_status     varchar(16) check (snapshot_status in ('PENDING', 'READY', 'FAILED')),
    add column outcome_note        varchar(1000);

-- US-37 / INV-28: lịch sử chuyển giao
create table alert_handover
(
    id              uuid primary key       default gen_random_uuid(),
    organization_id uuid          not null references organization (id),
    alert_id        uuid          not null references alert (id) on delete cascade,
    from_user       uuid          not null,
    to_user         uuid          not null,
    reason          varchar(1000) not null,
    handed_at       timestamptz   not null default now()
);
create index ix_alert_handover on alert_handover (alert_id, handed_at);
select camhub_enable_tenant_rls('alert_handover');

-- Kênh nhận của từng người dùng (UserChannelBinding): chat id Telegram, user id Zalo
create table user_channel_binding
(
    id              uuid primary key      default gen_random_uuid(),
    organization_id uuid         not null references organization (id),
    user_id         uuid         not null references app_user (id) on delete cascade,
    channel         varchar(16)  not null check (channel in ('TELEGRAM', 'ZALO')),
    address         varchar(200) not null,
    enabled         boolean      not null default true,
    created_at      timestamptz  not null default now(),
    unique (user_id, channel)
);
select camhub_enable_tenant_rls('user_channel_binding');

-- Cấu hình kênh của Tổ chức (Hệ thống > Kênh thông báo): token bot Telegram, access token Zalo OA (mã hóa)
create table notification_channel_config
(
    organization_id uuid        not null references organization (id),
    channel         varchar(16) not null check (channel in ('EMAIL', 'TELEGRAM', 'ZALO')),
    enabled         boolean     not null default false,
    secret_enc      text,
    updated_by      uuid,
    updated_at      timestamptz not null default now(),
    primary key (organization_id, channel)
);
select camhub_enable_tenant_rls('notification_channel_config');

alter table notification drop constraint notification_channel_check;
alter table notification
    add constraint notification_channel_check check (channel in ('WEB', 'EMAIL', 'TELEGRAM', 'ZALO')),
    add column attachment_bucket varchar(100),
    add column attachment_key    varchar(300);

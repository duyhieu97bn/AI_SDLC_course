-- TS-06/US-06 Bộ kết nối địa điểm (đường hầm WSS 443, ADR-10), US-03 đầu ghi NVR/DVR.

create table connector
(
    id                  uuid primary key      default gen_random_uuid(),
    organization_id     uuid         not null references organization (id),
    site_id             uuid         not null references site (id),
    name                varchar(200) not null,
    -- PENDING_ACTIVATION → ONLINE ⇄ OFFLINE; REVOKED khi Admin thu hồi
    status              varchar(24)  not null default 'PENDING_ACTIVATION'
        check (status in ('PENDING_ACTIVATION', 'ONLINE', 'OFFLINE', 'REVOKED')),
    -- Mã kích hoạt dùng 1 lần, hết hạn 24 giờ; chỉ lưu SHA-256
    activation_code_hash varchar(64) unique,
    code_expires_at     timestamptz,
    -- Bí mật API của Connector (Basic connectorId:secret), chỉ lưu SHA-256
    secret_hash         varchar(64),
    -- Tài khoản đường hầm chisel; mật khẩu mã hóa bằng khóa Tổ chức (ghi authfile cho Tunnel Server)
    tunnel_password_enc text,
    socks_port          int unique check (socks_port between 21000 and 21999),
    hostname            varchar(200),
    version             varchar(50),
    last_seen_at        timestamptz,
    tunnel_up           boolean      not null default false,
    rx_kbps             double precision,
    tx_kbps             double precision,
    created_by          uuid,
    created_at          timestamptz  not null default now(),
    activated_at        timestamptz
);
-- Mỗi địa điểm tối đa một bộ kết nối đang dùng
create unique index ux_connector_site on connector (site_id) where status <> 'REVOKED';
select camhub_enable_tenant_rls('connector');

-- Cổng chuyển tiếp ngược R:<port>:<host>:<targetPort> cho luồng RTSP của camera sau đường hầm
create table connector_forward
(
    id              uuid primary key     default gen_random_uuid(),
    organization_id uuid        not null references organization (id),
    connector_id    uuid        not null references connector (id) on delete cascade,
    host            varchar(255) not null,
    target_port     int         not null,
    port            int         not null unique check (port between 22000 and 23999),
    created_at      timestamptz not null default now(),
    unique (connector_id, host, target_port)
);
select camhub_enable_tenant_rls('connector_forward');

-- Việc giao cho Connector (dò WS-Discovery tại địa điểm)
create table connector_task
(
    id              uuid primary key     default gen_random_uuid(),
    organization_id uuid        not null references organization (id),
    connector_id    uuid        not null references connector (id) on delete cascade,
    type            varchar(24) not null check (type in ('DISCOVERY')),
    timeout_seconds int         not null default 5,
    status          varchar(16) not null default 'PENDING' check (status in ('PENDING', 'SENT', 'DONE', 'FAILED')),
    result          jsonb,
    error           varchar(500),
    created_at      timestamptz not null default now(),
    completed_at    timestamptz
);
create index ix_connector_task_pending on connector_task (connector_id) where status = 'PENDING';
select camhub_enable_tenant_rls('connector_task');

create table recorder
(
    id                uuid primary key      default gen_random_uuid(),
    organization_id   uuid         not null references organization (id),
    site_id           uuid         not null references site (id),
    zone_id           uuid         not null references zone (id),
    name              varchar(200) not null,
    host              varchar(255) not null,
    port              int          not null,
    username          varchar(100),
    password_enc      text,
    manufacturer      varchar(100),
    model             varchar(100),
    channel_count     int          not null,
    status            varchar(16)  not null default 'UNKNOWN' check (status in ('ONLINE', 'OFFLINE', 'UNKNOWN')),
    status_changed_at timestamptz,
    connector_id      uuid references connector (id),
    created_by        uuid,
    created_at        timestamptz  not null default now()
);
select camhub_enable_tenant_rls('recorder');

alter table camera
    add column recorder_id  uuid references recorder (id) on delete cascade,
    add column channel_no   int,
    add column connector_id uuid references connector (id);

-- Đăng nhập Connector chưa biết Tổ chức: tra theo mã kích hoạt hoặc id
create function camhub_connector_by_code(code_hash text)
    returns table (id uuid, organization_id uuid)
    language sql stable security definer set search_path = public
as
$$
select id, organization_id from connector where activation_code_hash = code_hash
$$;

create function camhub_connector_org(p_id uuid)
    returns uuid
    language sql stable security definer set search_path = public
as
$$
select organization_id from connector where id = p_id
$$;

create function camhub_recorder_org(p_id uuid)
    returns uuid
    language sql stable security definer set search_path = public
as
$$
select organization_id from recorder where id = p_id
$$;

-- Bộ kết nối đã kích hoạt của mọi Tổ chức: ghi authfile Tunnel Server, kiểm tra mất liên lạc
create function camhub_active_connectors()
    returns table (id uuid, organization_id uuid)
    language sql stable security definer set search_path = public
as
$$
select id, organization_id from connector where status in ('ONLINE', 'OFFLINE')
$$;

revoke all on function camhub_connector_by_code(text), camhub_connector_org(uuid), camhub_recorder_org(uuid),
    camhub_active_connectors() from public;
grant execute on function camhub_connector_by_code(text), camhub_connector_org(uuid), camhub_recorder_org(uuid),
    camhub_active_connectors() to ${app_role};

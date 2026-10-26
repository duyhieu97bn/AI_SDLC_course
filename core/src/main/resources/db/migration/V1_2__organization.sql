-- US-05 Tổ chức camera: địa điểm, khu vực, nhóm, lịch sử vị trí. Kèm khung bảng camera (S2 bổ sung cột).

create table site
(
    id              uuid primary key      default gen_random_uuid(),
    organization_id uuid         not null references organization (id),
    name            varchar(200) not null,
    address         varchar(500),
    timezone        varchar(64)  not null default 'Asia/Ho_Chi_Minh',
    created_at      timestamptz  not null default now(),
    unique (organization_id, name)
);
select camhub_enable_tenant_rls('site');

create table zone
(
    id              uuid primary key      default gen_random_uuid(),
    organization_id uuid         not null references organization (id),
    site_id         uuid         not null references site (id),
    name            varchar(200) not null,
    created_at      timestamptz  not null default now(),
    unique (site_id, name),
    unique (id, site_id) -- cho khóa ngoại kép từ camera (INV-02)
);
select camhub_enable_tenant_rls('zone');

create table camera_group
(
    id              uuid primary key      default gen_random_uuid(),
    organization_id uuid         not null references organization (id),
    name            varchar(200) not null,
    description     varchar(500),
    created_at      timestamptz  not null default now(),
    unique (organization_id, name)
);
select camhub_enable_tenant_rls('camera_group');

create table camera
(
    id              uuid primary key      default gen_random_uuid(),
    organization_id uuid         not null references organization (id),
    site_id         uuid         not null,
    zone_id         uuid         not null,
    name            varchar(200) not null,
    protocol        varchar(8)   not null default 'ONVIF' check (protocol in ('ONVIF', 'RTSP')),
    ip_address      varchar(64),
    port            int check (port between 1 and 65535),
    stream_url      text,
    manufacturer    varchar(100),
    model           varchar(100),
    mac_address     varchar(32),
    status          varchar(16)  not null default 'UNKNOWN' check (status in ('ONLINE', 'OFFLINE', 'UNKNOWN')),
    last_offline_at timestamptz,
    created_at      timestamptz  not null default now(),
    updated_at      timestamptz  not null default now(),
    -- INV-02: camera thuộc đúng 1 khu vực; site_id luôn là địa điểm của khu vực đó
    foreign key (zone_id, site_id) references zone (id, site_id),
    -- INV-03
    constraint ck_camera_protocol check (
        (protocol = 'RTSP' and stream_url is not null) or (protocol = 'ONVIF' and ip_address is not null and port is not null))
);
create index ix_camera_org_site on camera (organization_id, site_id);
create index ix_camera_zone on camera (zone_id);
create index ix_camera_name on camera (organization_id, lower(name));
select camhub_enable_tenant_rls('camera');

create table camera_group_member
(
    organization_id uuid not null references organization (id),
    group_id        uuid not null references camera_group (id) on delete cascade,
    camera_id       uuid not null references camera (id) on delete cascade,
    primary key (group_id, camera_id)
);
create index ix_camera_group_member_camera on camera_group_member (camera_id);
select camhub_enable_tenant_rls('camera_group_member');

-- INV-18, INV-19: chuyển camera không đụng tới video; lịch sử vị trí giữ khoảng thời gian ở mỗi khu vực
create table camera_location_history
(
    id              uuid primary key     default gen_random_uuid(),
    organization_id uuid        not null references organization (id),
    camera_id       uuid        not null references camera (id) on delete cascade,
    site_id         uuid        not null references site (id),
    zone_id         uuid        not null references zone (id),
    valid_from      timestamptz not null default now(),
    valid_to        timestamptz,
    moved_by        uuid
);
create unique index ux_camera_location_current on camera_location_history (camera_id) where valid_to is null;
select camhub_enable_tenant_rls('camera_location_history');

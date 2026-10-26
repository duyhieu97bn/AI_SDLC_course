-- US-10 Bố cục xem (ViewLayout, LayoutTile – INV-13) và US-13 Preset PTZ (PtzPreset).

create table view_layout
(
    id              uuid primary key      default gen_random_uuid(),
    organization_id uuid         not null references organization (id),
    user_id         uuid         not null references app_user (id) on delete cascade,
    name            varchar(100) not null,
    grid_size       int          not null check (grid_size in (1, 4, 9, 16)),
    last_used_at    timestamptz,
    created_at      timestamptz  not null default now(),
    unique (user_id, name)
);
select camhub_enable_tenant_rls('view_layout');

create table layout_tile
(
    organization_id uuid       not null references organization (id),
    layout_id       uuid       not null references view_layout (id) on delete cascade,
    position        int        not null check (position between 0 and 15),
    -- Camera bị xóa thì ô bị bỏ khỏi bố cục
    camera_id       uuid       not null references camera (id) on delete cascade,
    stream_mode     varchar(8) not null default 'AUTO' check (stream_mode in ('AUTO', 'HIGH', 'LOW')),
    primary key (layout_id, position)
);
select camhub_enable_tenant_rls('layout_tile');

create table ptz_preset
(
    id              uuid primary key      default gen_random_uuid(),
    organization_id uuid         not null references organization (id),
    camera_id       uuid         not null references camera (id) on delete cascade,
    name            varchar(100) not null,
    device_token    varchar(100) not null,
    source          varchar(8)   not null default 'CAMHUB' check (source in ('DEVICE', 'CAMHUB')),
    created_by      uuid,
    created_at      timestamptz  not null default now(),
    unique (camera_id, device_token)
);
select camhub_enable_tenant_rls('ptz_preset');

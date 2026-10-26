-- US-01, US-02, US-04: camera đầy đủ (tài khoản mã hóa, khả năng, luồng), đường dẫn MediaMTX, trạng thái.

alter table camera
    add column username        varchar(100),
    add column password_enc    text,          -- INV-17: AES-256-GCM qua CryptoService
    add column firmware        varchar(100),
    add column serial_number   varchar(100),
    add column capabilities    jsonb not null default '{}'::jsonb,
    -- Tên path trên MediaMTX (không lộ IP/tài khoản camera); luồng phụ là "<media_path>-sub"
    add column media_path      varchar(64),
    add column status_changed_at timestamptz,
    add column verified        boolean not null default false;

update camera set media_path = 'cam-' || replace(id::text, '-', '') where media_path is null;
alter table camera alter column media_path set default ('cam-' || replace(gen_random_uuid()::text, '-', ''));
alter table camera alter column media_path set not null;
create unique index ux_camera_media_path on camera (media_path);
create index ix_camera_mac on camera (organization_id, lower(mac_address)) where mac_address is not null;

-- INV-04: tối đa một luồng mỗi loại
create table camera_stream
(
    organization_id uuid        not null references organization (id),
    camera_id       uuid        not null references camera (id) on delete cascade,
    stream_type     varchar(8)  not null check (stream_type in ('MAIN', 'SUB')),
    profile_token   varchar(100),
    codec           varchar(16),
    width           int,
    height          int,
    fps             int,
    bitrate_kbps    int,
    -- URL RTSP thiết bị báo, KHÔNG chứa tài khoản; Core ghép tài khoản khi cấu hình MediaMTX
    rtsp_url        text        not null,
    b_frames        boolean     not null default false,
    primary key (camera_id, stream_type)
);
select camhub_enable_tenant_rls('camera_stream');

-- Sự kiện thiết bị (RabbitMQ) chỉ có cameraId: tra Tổ chức trước khi xử lý dưới RLS
create function camhub_camera_org(p_camera_id uuid) returns uuid
    language sql stable security definer set search_path = public
as $$ select organization_id from camera where id = p_camera_id $$;

-- Đồng bộ MediaMTX và danh sách theo dõi kết nối cho mọi Tổ chức (tác vụ hệ thống)
create function camhub_all_camera_refs()
    returns table (camera_id uuid, organization_id uuid)
    language sql stable security definer set search_path = public
as $$ select id, organization_id from camera $$;

-- MediaMTX hỏi quyền theo path: tra camera và Tổ chức từ tên path
create function camhub_camera_by_path(p_path text)
    returns table (camera_id uuid, organization_id uuid)
    language sql stable security definer set search_path = public
as $$
    select id, organization_id from camera
    where media_path = regexp_replace(p_path, '(-sub)?(_h264)?$', '')
$$;

revoke all on function camhub_camera_org(uuid), camhub_all_camera_refs(), camhub_camera_by_path(text) from public;
grant execute on function camhub_camera_org(uuid), camhub_all_camera_refs(), camhub_camera_by_path(text) to ${app_role};

-- US-16 Chế độ ghi hình, US-17 Xem lại: chính sách ghi theo camera, chỉ mục đoạn ghi (phân vùng tháng), mốc sự kiện.

create table recording_policy
(
    camera_id          uuid primary key references camera (id) on delete cascade,
    organization_id    uuid        not null references organization (id),
    mode               varchar(12) not null default 'OFF' check (mode in ('CONTINUOUS', 'SCHEDULED', 'EVENT', 'OFF')),
    -- Lịch tuần: [{"day":0..6 (0 = Thứ Hai),"from":"HH:mm","to":"HH:mm"}] theo múi giờ của địa điểm
    schedule           jsonb       not null default '[]'::jsonb,
    pre_event_seconds  int         not null default 10 check (pre_event_seconds between 0 and 60),
    post_event_seconds int         not null default 30 check (post_event_seconds between 0 and 600),
    updated_by         uuid,
    updated_at         timestamptz not null default now()
);
select camhub_enable_tenant_rls('recording_policy');

-- INV-18: đoạn ghi gắn với camera, không gắn khu vực; chuyển camera không đụng tới video
create table recording_segment
(
    id               uuid         not null default gen_random_uuid(),
    organization_id  uuid         not null references organization (id),
    camera_id        uuid         not null,
    start_at         timestamptz  not null,
    end_at           timestamptz  not null,
    source           varchar(8)   not null default 'CAMHUB' check (source in ('CAMHUB', 'DEVICE')),
    storage_location varchar(500) not null,
    size_bytes       bigint       not null default 0,
    has_event        boolean      not null default false,
    created_at       timestamptz  not null default now(),
    primary key (start_at, id),
    check (end_at >= start_at)
) partition by range (start_at);
create index ix_segment_camera_time on recording_segment (camera_id, start_at);
create index ix_segment_org_end on recording_segment (organization_id, end_at);
alter table recording_segment enable row level security;
create policy tenant_isolation on recording_segment using (organization_id = camhub_current_org())
    with check (organization_id = camhub_current_org());

-- Mốc chuyển động dùng cho ghi theo sự kiện và hiển thị trên timeline (US-16 AC1, US-17 AC3)
create table recording_event
(
    id              uuid primary key     default gen_random_uuid(),
    organization_id uuid        not null references organization (id),
    camera_id       uuid        not null references camera (id) on delete cascade,
    occurred_at     timestamptz not null,
    kind            varchar(16) not null default 'MOTION'
);
create index ix_recording_event_camera on recording_event (camera_id, occurred_at);
select camhub_enable_tenant_rls('recording_event');

create function camhub_segment_ensure_partitions(months_back int default 1, months_ahead int default 3) returns int
    language plpgsql security definer set search_path = public
as $$
declare
    start_month date := date_trunc('month', now())::date;
    m date;
    part text;
    created int := 0;
begin
    for i in -months_back..months_ahead loop
        m := (start_month + make_interval(months => i))::date;
        part := format('recording_segment_%s', to_char(m, 'YYYY_MM'));
        if to_regclass(part) is null then
            execute format('create table %I partition of recording_segment for values from (%L) to (%L)',
                           part, m, (m + interval '1 month')::date);
            created := created + 1;
        end if;
    end loop;
    return created;
end
$$;
revoke all on function camhub_segment_ensure_partitions(int, int) from public;
grant execute on function camhub_segment_ensure_partitions(int, int) to ${app_role};
select camhub_segment_ensure_partitions(1, 12);

-- Tác vụ hệ thống (xóa video quá hạn, lập lịch ghi) duyệt mọi Tổ chức
create function camhub_organization_ids() returns setof uuid
    language sql stable security definer set search_path = public
as $$ select id from organization $$;
revoke all on function camhub_organization_ids() from public;
grant execute on function camhub_organization_ids() to ${app_role};

-- Trạng thái ghi mong muốn của camera (module device giữ, đồng bộ lên MediaMTX); module recording quyết định theo chính sách
alter table camera add column recording_enabled boolean not null default false;

-- TS-04.2 Migration nền: Tổ chức, outbox, hàm bật RLS dùng chung cho mọi bảng theo Tổ chức.
-- Chạy bằng tài khoản owner. Tài khoản ứng dụng ${app_role} chỉ có quyền DML và luôn chịu RLS.

-- Tổ chức hiện tại của phiên, do TenantAwareDataSource đặt. Rỗng => NULL => không khớp dòng nào.
create function camhub_current_org() returns uuid
    language sql stable
as $$ select nullif(current_setting('app.org_id', true), '')::uuid $$;

-- Bật RLS cho một bảng theo cột organization_id. Mọi migration tạo bảng nghiệp vụ gọi hàm này (INV-01).
create function camhub_enable_tenant_rls(tbl regclass, org_column text default 'organization_id') returns void
    language plpgsql
as $$
begin
    execute format('alter table %s enable row level security', tbl);
    execute format(
        'create policy tenant_isolation on %s using (%I = camhub_current_org()) with check (%I = camhub_current_org())',
        tbl, org_column, org_column);
end
$$;

create table organization
(
    id           uuid primary key      default gen_random_uuid(),
    code         varchar(64)  not null unique,
    name         varchar(255) not null,
    status       varchar(16)  not null default 'ACTIVE' check (status in ('ACTIVE', 'SUSPENDED')),
    -- Khóa dữ liệu của Tổ chức, đã mã hóa bằng khóa chính: "<mã khóa chính>:<base64>" (CryptoService)
    data_key_enc text,
    created_at   timestamptz  not null default now()
);

-- Bảng organization lọc theo chính id
alter table organization enable row level security;
create policy tenant_isolation on organization
    using (id = camhub_current_org())
    with check (id = camhub_current_org());

-- Outbox không bật RLS: chỉ OutboxPublisher (cấp hệ thống) đọc, không có API nào trả bảng này ra ngoài.
create table outbox_event
(
    id              uuid primary key,
    organization_id uuid references organization (id),
    aggregate_type  varchar(64)  not null,
    aggregate_id    varchar(64)  not null,
    event_type      varchar(128) not null,
    exchange        varchar(64)  not null,
    routing_key     varchar(128) not null,
    payload         jsonb        not null,
    created_at      timestamptz  not null default now(),
    published_at    timestamptz,
    attempts        int          not null default 0,
    next_attempt_at timestamptz  not null default now(),
    last_error      text
);

create index ix_outbox_event_pending on outbox_event (next_attempt_at) where published_at is null;
create index ix_outbox_event_published on outbox_event (published_at) where published_at is not null;

grant usage on schema public to ${app_role};
grant select, insert, update, delete on all tables in schema public to ${app_role};
alter default privileges in schema public grant select, insert, update, delete on tables to ${app_role};
alter default privileges in schema public grant usage, select on sequences to ${app_role};

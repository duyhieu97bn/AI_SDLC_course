-- US-30 Đăng nhập: người dùng và phiên. Mọi bảng theo Tổ chức bật RLS (INV-01).

create table app_user
(
    id                      uuid primary key      default gen_random_uuid(),
    organization_id         uuid         not null references organization (id),
    email                   varchar(320) not null,
    display_name            varchar(200) not null,
    auth_source             varchar(16)  not null default 'LOCAL' check (auth_source in ('LOCAL', 'DIRECTORY')),
    external_id             varchar(512),
    directory_connection_id uuid,
    password_hash           varchar(255),
    status                  varchar(16)  not null default 'ACTIVE' check (status in ('ACTIVE', 'LOCKED', 'DISABLED')),
    language                varchar(8)   not null default 'vi' check (language in ('vi', 'en')),
    failed_login_count      int          not null default 0,
    locked_at               timestamptz,
    two_factor_enabled      boolean      not null default false,
    totp_secret_enc         text,
    created_by              uuid,
    created_at              timestamptz  not null default now(),
    updated_at              timestamptz  not null default now(),
    last_login_at           timestamptz,
    -- INV-23: tài khoản cục bộ bắt buộc có mật khẩu, tài khoản thư mục không lưu mật khẩu
    constraint ck_app_user_password check (
        (auth_source = 'LOCAL' and password_hash is not null)
            or (auth_source = 'DIRECTORY' and password_hash is null and external_id is not null))
);

-- Email là tên đăng nhập, duy nhất trên toàn hệ thống để đăng nhập không cần chọn Tổ chức (sẵn sàng SaaS)
create unique index ux_app_user_email on app_user (lower(email));
create index ix_app_user_org on app_user (organization_id);
select camhub_enable_tenant_rls('app_user');

create table user_session
(
    id                  uuid primary key,
    organization_id     uuid        not null references organization (id),
    user_id             uuid        not null references app_user (id) on delete cascade,
    -- SHA-256 (hex) của refresh token; token gốc chỉ nằm trong cookie HttpOnly của trình duyệt
    refresh_token_hash  char(64)    not null unique,
    -- Token trước lần xoay vòng gần nhất: dùng lại token cũ nghĩa là token bị lộ -> thu hồi phiên
    previous_token_hash char(64),
    ip_address          varchar(64),
    user_agent          varchar(512),
    started_at          timestamptz not null default now(),
    last_active_at      timestamptz not null default now(),
    revoked_at          timestamptz,
    revoke_reason       varchar(64)
);

create index ix_user_session_user on user_session (user_id) where revoked_at is null;
create index ix_user_session_prev on user_session (previous_token_hash) where previous_token_hash is not null;
select camhub_enable_tenant_rls('user_session');

-- Tra cứu trước khi biết Tổ chức (đăng nhập, làm mới phiên). SECURITY DEFINER chạy bằng quyền owner nên
-- vượt RLS, vì vậy chỉ trả về id và organization_id; mọi truy vấn tiếp theo chạy trong ngữ cảnh Tổ chức.
create function camhub_login_lookup(p_email text)
    returns table (user_id uuid, organization_id uuid)
    language sql stable security definer set search_path = public
as $$ select id, organization_id from app_user where lower(email) = lower(p_email) $$;

create function camhub_session_lookup(p_token_hash text)
    returns table (session_id uuid, organization_id uuid, reused boolean)
    language sql stable security definer set search_path = public
as $$
    select id, organization_id, false from user_session where refresh_token_hash = p_token_hash
    union all
    select id, organization_id, true from user_session where previous_token_hash = p_token_hash
$$;

create function camhub_organization_count() returns bigint
    language sql stable security definer set search_path = public
as $$ select count(*) from organization $$;

revoke all on function camhub_login_lookup(text), camhub_session_lookup(text), camhub_organization_count() from public;
grant execute on function camhub_login_lookup(text), camhub_session_lookup(text), camhub_organization_count() to ${app_role};

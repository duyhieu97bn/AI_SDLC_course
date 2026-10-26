-- US-44 Đăng nhập bằng LDAP/Active Directory: kết nối thư mục của Tổ chức và ánh xạ nhóm → vai trò (INV-23 đến INV-26).

create table directory_connection
(
    organization_id        uuid primary key references organization (id),
    enabled                boolean      not null default false,
    url                    varchar(300) not null,
    bind_dn                varchar(300) not null,
    bind_password_enc      text,
    user_base              varchar(300) not null,
    -- {0} là tên đăng nhập người dùng nhập (uid, sAMAccountName hoặc email)
    user_filter            varchar(300) not null default '(|(uid={0})(sAMAccountName={0})(mail={0}))',
    group_base             varchar(300) not null,
    member_attribute       varchar(100) not null default 'member',
    display_name_attribute varchar(100) not null default 'cn',
    mail_attribute         varchar(100) not null default 'mail',
    -- Người dùng thuộc nhóm này coi là bị vô hiệu hóa (OpenLDAP không có userAccountControl như AD)
    disabled_group_dn      varchar(300),
    last_sync_at           timestamptz,
    last_sync_status       varchar(16),
    last_sync_message      varchar(500),
    updated_by             uuid,
    updated_at             timestamptz  not null default now()
);
select camhub_enable_tenant_rls('directory_connection');

create table directory_group_mapping
(
    id              uuid primary key      default gen_random_uuid(),
    organization_id uuid         not null references organization (id),
    group_dn        varchar(300) not null,
    role_id         uuid         not null references role (id) on delete cascade,
    scope_type      varchar(16)  not null check (scope_type in ('ORGANIZATION', 'SITE', 'GROUP', 'CAMERA')),
    scope_id        uuid,
    check ((scope_type = 'ORGANIZATION') = (scope_id is null)),
    unique (organization_id, group_dn, role_id, scope_type, scope_id)
);
select camhub_enable_tenant_rls('directory_group_mapping');

-- Tổ chức bật đăng nhập thư mục: người dùng chưa có trong CamHub đăng nhập lần đầu sẽ được thử ở các Tổ chức này
create function camhub_directory_orgs()
    returns setof uuid
    language sql stable security definer set search_path = public
as
$$
select organization_id from directory_connection where enabled
$$;
revoke all on function camhub_directory_orgs() from public;
grant execute on function camhub_directory_orgs() to ${app_role};

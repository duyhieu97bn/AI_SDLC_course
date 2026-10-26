-- US-32 Phân quyền: vai trò, quyền, gán vai trò theo phạm vi. 5 vai trò hệ thống được tạo khi tạo Tổ chức
-- (OrganizationProvisioner), vì Tổ chức sinh động ở SaaS.

create table role
(
    id                 uuid primary key      default gen_random_uuid(),
    organization_id    uuid         not null references organization (id),
    code               varchar(32),           -- mã vai trò hệ thống (ADMIN, SITE_MANAGER...); null với vai trò tùy chỉnh
    name               varchar(100) not null,
    description        varchar(500),
    is_system          boolean      not null default false,
    require_two_factor boolean      not null default false,
    -- Thứ hạng: quyết định ai giành khóa PTZ (SADD 3.11.4); Admin cao nhất
    rank               int          not null default 0,
    created_at         timestamptz  not null default now(),
    unique (organization_id, name),
    unique (organization_id, code)
);
select camhub_enable_tenant_rls('role');

create table role_permission
(
    organization_id uuid        not null references organization (id),
    role_id         uuid        not null references role (id) on delete cascade,
    permission      varchar(32) not null check (permission in (
        'VIEW_LIVE', 'VIEW_PLAYBACK', 'EXPORT_VIDEO', 'CONTROL_PTZ', 'CONFIGURE_CAMERA',
        'MANAGE_USERS', 'MANAGE_ROLES', 'MANAGE_SYSTEM', 'HOLD_EVIDENCE', 'TALK_AUDIO')),
    primary key (role_id, permission)
);
select camhub_enable_tenant_rls('role_permission');

create table role_assignment
(
    id              uuid primary key     default gen_random_uuid(),
    organization_id uuid        not null references organization (id),
    user_id         uuid        not null references app_user (id) on delete cascade,
    role_id         uuid        not null references role (id),  -- không cascade: INV-21 chặn xóa vai trò đang dùng
    scope_type      varchar(16) not null check (scope_type in ('ORGANIZATION', 'SITE', 'GROUP', 'CAMERA')),
    scope_id        uuid,
    -- DIRECTORY: sinh từ ánh xạ nhóm LDAP, Admin không sửa tay (INV-26)
    source          varchar(16) not null default 'MANUAL' check (source in ('MANUAL', 'DIRECTORY')),
    created_by      uuid,
    created_at      timestamptz not null default now(),
    constraint ck_role_assignment_scope check ((scope_type = 'ORGANIZATION') = (scope_id is null))
);
create unique index ux_role_assignment on role_assignment
    (user_id, role_id, scope_type, coalesce(scope_id, '00000000-0000-0000-0000-000000000000'));
create index ix_role_assignment_role on role_assignment (role_id);
select camhub_enable_tenant_rls('role_assignment');

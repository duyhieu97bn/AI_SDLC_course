-- Chỉ có trong test: bảng mẫu theo Tổ chức để kiểm chứng camhub_enable_tenant_rls().
create table rls_probe
(
    id              bigserial primary key,
    organization_id uuid not null references organization (id),
    label           text not null
);

select camhub_enable_tenant_rls('rls_probe');

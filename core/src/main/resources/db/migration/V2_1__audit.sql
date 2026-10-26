-- US-33 Nhật ký thao tác (BR-30, RULE-06, INV-16): chỉ thêm, không sửa/xóa được, kể cả bằng tài khoản ứng dụng.
-- Phân vùng theo tháng để tra cứu nhanh và giữ ≥ 12 tháng; owner tạo trước phân vùng (camhub_audit_ensure_partitions).

create table audit_log
(
    id              uuid        not null default gen_random_uuid(),
    organization_id uuid        not null references organization (id),
    occurred_at     timestamptz not null default now(),
    actor_id        uuid,
    actor_name      varchar(200),
    action          varchar(48) not null,
    target_type     varchar(48),
    target_id       varchar(64),
    target_name     varchar(300),
    ip_address      varchar(64),
    result          varchar(8)  not null default 'SUCCESS' check (result in ('SUCCESS', 'FAILURE')),
    details         jsonb       not null default '{}'::jsonb,
    primary key (occurred_at, id)
) partition by range (occurred_at);

create index ix_audit_org_time on audit_log (organization_id, occurred_at desc);
create index ix_audit_action on audit_log (organization_id, action, occurred_at desc);
create index ix_audit_actor on audit_log (organization_id, actor_id, occurred_at desc);
create index ix_audit_target on audit_log (organization_id, target_id, occurred_at desc);

alter table audit_log enable row level security;
create policy tenant_isolation on audit_log using (organization_id = camhub_current_org())
    with check (organization_id = camhub_current_org());

-- Chặn sửa/xóa ở mức DB: trigger áp cho mọi phân vùng (PostgreSQL 13+)
create function camhub_audit_immutable() returns trigger
    language plpgsql
as $$
begin
    raise exception 'audit_log chỉ được thêm, không được sửa hoặc xóa (RULE-06)' using errcode = 'insufficient_privilege';
end
$$;

create trigger audit_log_no_update before update or delete on audit_log
    for each row execute function camhub_audit_immutable();

revoke update, delete, truncate on audit_log from ${app_role};
grant select, insert on audit_log to ${app_role};

-- Tạo phân vùng cho tháng hiện tại và {months_ahead} tháng tới; quyền trên phân vùng con cũng chỉ SELECT/INSERT
create function camhub_audit_ensure_partitions(months_ahead int default 3) returns int
    language plpgsql security definer set search_path = public
as $$
declare
    start_month date := date_trunc('month', now())::date;
    m date;
    part text;
    created int := 0;
begin
    for i in 0..months_ahead loop
        m := (start_month + make_interval(months => i))::date;
        part := format('audit_log_%s', to_char(m, 'YYYY_MM'));
        if to_regclass(part) is null then
            execute format('create table %I partition of audit_log for values from (%L) to (%L)',
                           part, m, (m + interval '1 month')::date);
            execute format('revoke all on %I from ${app_role}', part);
            created := created + 1;
        end if;
    end loop;
    return created;
end
$$;

revoke all on function camhub_audit_ensure_partitions(int) from public;
grant execute on function camhub_audit_ensure_partitions(int) to ${app_role};

select camhub_audit_ensure_partitions(12);

alter table platform_user
    add column password_hash varchar(255),
    add column platform_role varchar(48),
    add column must_change_password boolean not null default false,
    add column failed_login_count integer not null default 0,
    add column locked_until timestamptz,
    add column password_changed_at timestamptz,
    add column auth_version bigint not null default 0,
    add constraint platform_user_platform_role_check
        check (platform_role is null or platform_role = 'PLATFORM_SUPER_ADMIN'),
    add constraint platform_user_failed_login_count_check
        check (failed_login_count >= 0);

create unique index platform_user_single_super_admin
    on platform_user (platform_role)
    where platform_role = 'PLATFORM_SUPER_ADMIN';

create table user_tenant_authority (
    tenant_id uuid not null references tenant(id) on delete cascade,
    user_id uuid not null references platform_user(id) on delete cascade,
    role_code varchar(64) not null,
    status varchar(24) not null,
    invite_expires_at timestamptz,
    accepted_at timestamptz,
    updated_at timestamptz not null default now(),
    primary key (tenant_id, user_id, role_code)
);

alter table tenant_membership no force row level security;
insert into user_tenant_authority
    (tenant_id, user_id, role_code, status, invite_expires_at, accepted_at, updated_at)
select tenant_id, user_id, role_code, status, invite_expires_at, accepted_at, updated_at
  from tenant_membership;
alter table tenant_membership force row level security;

create or replace function sync_user_tenant_authority()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
begin
    if tg_op = 'DELETE' then
        delete from user_tenant_authority
         where tenant_id=old.tenant_id and user_id=old.user_id and role_code=old.role_code;
        return old;
    end if;
    if tg_op = 'UPDATE' and (old.tenant_id, old.user_id, old.role_code)
            is distinct from (new.tenant_id, new.user_id, new.role_code) then
        delete from user_tenant_authority
         where tenant_id=old.tenant_id and user_id=old.user_id and role_code=old.role_code;
    end if;
    insert into user_tenant_authority
        (tenant_id, user_id, role_code, status, invite_expires_at, accepted_at, updated_at)
    values
        (new.tenant_id, new.user_id, new.role_code, new.status,
         new.invite_expires_at, new.accepted_at, now())
    on conflict (tenant_id, user_id, role_code) do update
       set status=excluded.status,
           invite_expires_at=excluded.invite_expires_at,
           accepted_at=excluded.accepted_at,
           updated_at=now();
    return new;
end
$$;

revoke all on function sync_user_tenant_authority() from public;

create trigger tenant_membership_authority_sync
after insert or update or delete on tenant_membership
for each row execute function sync_user_tenant_authority();

create index user_tenant_authority_user_idx
    on user_tenant_authority (user_id, status, tenant_id);

create table admin_refresh_token (
    id uuid primary key,
    user_id uuid not null references platform_user(id) on delete cascade,
    token_hash char(64) not null unique,
    family_id uuid not null,
    expires_at timestamptz not null,
    revoked_at timestamptz,
    replaced_by uuid references admin_refresh_token(id),
    created_at timestamptz not null default now(),
    last_used_at timestamptz
);

create index admin_refresh_token_user_family_idx
    on admin_refresh_token (user_id, family_id, expires_at);

create table admin_login_event (
    id uuid primary key,
    user_id uuid references platform_user(id) on delete set null,
    username varchar(160) not null,
    source_ip varchar(64),
    result varchar(32) not null check (result in ('SUCCESS', 'FAILURE', 'LOCKED')),
    failure_reason varchar(64),
    occurred_at timestamptz not null default now()
);

create index admin_login_event_user_time_idx
    on admin_login_event (user_id, occurred_at desc);
create index admin_login_event_username_time_idx
    on admin_login_event (lower(username), occurred_at desc);

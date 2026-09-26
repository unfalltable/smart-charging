create table operator_organization (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    parent_id uuid,
    code varchar(64) not null,
    name varchar(160) not null,
    organization_type varchar(32) not null check (organization_type in (
        'REGIONAL_OPERATOR', 'FIRST_TIER_CONTRACTOR', 'DIRECT_BRANCH',
        'SECOND_TIER_PARTNER', 'SITE_PARTNER'
    )),
    hierarchy_level smallint not null check (hierarchy_level in (1, 2)),
    contact_name varchar(120),
    contact_mobile varchar(32),
    status varchar(24) not null check (status in ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0,
    unique (tenant_id, id),
    unique (tenant_id, code),
    foreign key (tenant_id, parent_id) references operator_organization(tenant_id, id),
    check (
        (hierarchy_level = 1 and parent_id is null and organization_type in (
            'REGIONAL_OPERATOR', 'FIRST_TIER_CONTRACTOR', 'DIRECT_BRANCH'
        )) or
        (hierarchy_level = 2 and parent_id is not null and organization_type in (
            'SECOND_TIER_PARTNER', 'SITE_PARTNER'
        ))
    )
);

create unique index operator_organization_one_root_per_tenant
    on operator_organization (tenant_id) where hierarchy_level = 1;
create index operator_organization_parent_idx
    on operator_organization (tenant_id, parent_id, status);

-- Every existing tenant becomes a real first-tier operating organization. Reusing
-- the tenant UUID makes the migration deterministic and keeps all existing assets
-- addressable without generating placeholder organizations.
insert into operator_organization
    (id, tenant_id, parent_id, code, name, organization_type, hierarchy_level, status)
select id, id, null, code, display_name, 'REGIONAL_OPERATOR', 1, status
  from tenant;

create function initialize_tenant_root_organization() returns trigger
language plpgsql as $$
declare
    previous_tenant text;
begin
    previous_tenant := current_setting('app.tenant_id', true);
    perform set_config('app.tenant_id', new.id::text, true);
    insert into operator_organization
        (id, tenant_id, parent_id, code, name, organization_type, hierarchy_level, status)
    values (new.id, new.id, null, new.code, new.display_name, 'REGIONAL_OPERATOR', 1, new.status);
    perform set_config('app.tenant_id', coalesce(previous_tenant, ''), true);
    return new;
end $$;

create trigger tenant_root_organization_initialize
after insert on tenant
for each row execute function initialize_tenant_root_organization();

alter table station add column organization_id uuid;
update station set organization_id = tenant_id;
alter table station alter column organization_id set not null;
alter table station add constraint station_organization_fk
    foreign key (tenant_id, organization_id) references operator_organization(tenant_id, id);
create index station_organization_idx on station (tenant_id, organization_id, status);

create function assign_station_root_organization() returns trigger
language plpgsql as $$
begin
    if new.organization_id is null then
        new.organization_id := new.tenant_id;
    end if;
    return new;
end $$;

create trigger station_root_organization_default
before insert on station
for each row execute function assign_station_root_organization();

alter table settlement_rule add column organization_id uuid;
alter table settlement_rule add column platform_service_fee_basis_points integer not null default 0
    check (platform_service_fee_basis_points between 0 and 10000);
alter table settlement_rule add column fixed_service_fee_minor bigint not null default 0
    check (fixed_service_fee_minor >= 0);
update settlement_rule set organization_id = tenant_id;
alter table settlement_rule alter column organization_id set not null;
alter table settlement_rule add constraint settlement_rule_organization_fk
    foreign key (tenant_id, organization_id) references operator_organization(tenant_id, id);

alter table settlement_statement add column platform_service_fee_minor bigint not null default 0
    check (platform_service_fee_minor >= 0);

alter table operator_organization enable row level security;
alter table operator_organization force row level security;
create policy tenant_isolation on operator_organization
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

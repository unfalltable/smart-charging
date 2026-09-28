alter table operator_organization drop constraint if exists operator_organization_organization_type_check;
alter table operator_organization drop constraint if exists operator_organization_hierarchy_level_check;
alter table operator_organization drop constraint if exists operator_organization_check;

alter table operator_organization add constraint operator_organization_type_check
    check (organization_type in (
        'REGIONAL_OPERATOR', 'FIRST_TIER_CONTRACTOR', 'DIRECT_BRANCH',
        'SECOND_TIER_PARTNER', 'THIRD_TIER_FRANCHISE', 'SITE_PARTNER'
    ));
alter table operator_organization add constraint operator_organization_level_check
    check (hierarchy_level between 1 and 3);
alter table operator_organization add constraint operator_organization_level_type_check
    check (
        (hierarchy_level = 1 and parent_id is null and organization_type in (
            'REGIONAL_OPERATOR', 'FIRST_TIER_CONTRACTOR', 'DIRECT_BRANCH'
        )) or
        (hierarchy_level = 2 and parent_id is not null and organization_type in (
            'SECOND_TIER_PARTNER', 'SITE_PARTNER'
        )) or
        (hierarchy_level = 3 and parent_id is not null and organization_type in (
            'THIRD_TIER_FRANCHISE', 'SITE_PARTNER'
        ))
    );

alter table merchant_channel add column profit_sharing_required boolean not null default true;
alter table payment_transaction add column profit_sharing_required boolean not null default false;
alter table refund_transaction add constraint refund_transaction_tenant_id_id_key unique (tenant_id, id);

create table profit_sharing_receiver (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    owner_type varchar(24) not null check (owner_type in ('PLATFORM', 'ORGANIZATION')),
    organization_id uuid,
    channel varchar(24) not null check (channel in ('WECHAT', 'ALIPAY')),
    receiver_type varchar(24) not null check (receiver_type = 'MERCHANT_ID'),
    receiver_account varchar(64) not null,
    receiver_name varchar(160) not null,
    relation_type varchar(32) not null check (relation_type in (
        'SERVICE_PROVIDER', 'STORE', 'STORE_OWNER', 'PARTNER', 'HEADQUARTER',
        'BRAND', 'DISTRIBUTOR', 'SUPPLIER', 'CUSTOM'
    )),
    custom_relation varchar(10),
    status varchar(24) not null check (status in ('PENDING', 'ACTIVE', 'FAILED', 'DISABLED')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0,
    unique (tenant_id, id),
    unique (tenant_id, channel, receiver_account),
    foreign key (tenant_id, organization_id) references operator_organization(tenant_id, id),
    check ((owner_type = 'PLATFORM' and organization_id is null)
        or (owner_type = 'ORGANIZATION' and organization_id is not null)),
    check ((relation_type = 'CUSTOM' and custom_relation is not null)
        or (relation_type <> 'CUSTOM' and custom_relation is null))
);

create unique index profit_sharing_one_platform_receiver_idx
    on profit_sharing_receiver (tenant_id, channel)
    where owner_type = 'PLATFORM' and status in ('PENDING', 'ACTIVE');
create unique index profit_sharing_one_org_receiver_idx
    on profit_sharing_receiver (tenant_id, organization_id, channel)
    where owner_type = 'ORGANIZATION' and status in ('PENDING', 'ACTIVE');

create table profit_sharing_policy (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    source_organization_id uuid not null,
    receiver_id uuid not null,
    basis_points integer not null check (basis_points between 1 and 10000),
    effective_from date not null,
    effective_until date,
    status varchar(24) not null check (status in ('ACTIVE', 'DISABLED')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0,
    unique (tenant_id, id),
    foreign key (tenant_id, source_organization_id)
        references operator_organization(tenant_id, id),
    foreign key (tenant_id, receiver_id) references profit_sharing_receiver(tenant_id, id),
    check (effective_until is null or effective_until >= effective_from)
);

create unique index profit_sharing_policy_active_receiver_idx
    on profit_sharing_policy (tenant_id, source_organization_id, receiver_id)
    where status = 'ACTIVE';
create index profit_sharing_policy_lookup_idx
    on profit_sharing_policy (tenant_id, source_organization_id, effective_from, effective_until)
    where status = 'ACTIVE';

create table payment_profit_sharing_order (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    payment_id uuid not null,
    channel varchar(24) not null check (channel in ('WECHAT', 'ALIPAY')),
    out_order_no varchar(64) not null,
    provider_order_no varchar(64),
    amount_minor bigint not null check (amount_minor >= 0),
    status varchar(24) not null check (status in (
        'PLANNED', 'PROCESSING', 'SUCCEEDED', 'PARTIAL_FAILED', 'FAILED', 'CANCELLED'
    )),
    attempts integer not null default 0 check (attempts >= 0),
    next_attempt_at timestamptz not null default now(),
    last_error varchar(500),
    completed_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (tenant_id, id),
    unique (tenant_id, payment_id),
    unique (channel, out_order_no),
    foreign key (tenant_id, payment_id) references payment_transaction(tenant_id, id)
);

create table payment_profit_sharing_detail (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    sharing_order_id uuid not null,
    receiver_id uuid not null,
    owner_type varchar(24) not null check (owner_type in ('PLATFORM', 'ORGANIZATION')),
    organization_id uuid,
    receiver_type varchar(24) not null check (receiver_type = 'MERCHANT_ID'),
    receiver_account varchar(64) not null,
    receiver_name varchar(160) not null,
    basis_points integer not null check (basis_points between 1 and 10000),
    amount_minor bigint not null check (amount_minor > 0),
    status varchar(24) not null check (status in ('PENDING', 'SUCCESS', 'FAILED', 'CANCELLED')),
    fail_reason varchar(160),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (tenant_id, id),
    unique (tenant_id, sharing_order_id, receiver_account),
    foreign key (tenant_id, sharing_order_id) references payment_profit_sharing_order(tenant_id, id),
    foreign key (tenant_id, receiver_id) references profit_sharing_receiver(tenant_id, id),
    foreign key (tenant_id, organization_id) references operator_organization(tenant_id, id)
);

create table payment_profit_sharing_return (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    refund_id uuid not null,
    sharing_order_id uuid not null,
    sharing_detail_id uuid not null,
    out_return_no varchar(64) not null,
    provider_return_no varchar(64),
    receiver_account varchar(64) not null,
    amount_minor bigint not null check (amount_minor > 0),
    status varchar(24) not null check (status in ('PLANNED', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    attempts integer not null default 0 check (attempts >= 0),
    next_attempt_at timestamptz not null default now(),
    last_error varchar(500),
    completed_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (tenant_id, id),
    unique (tenant_id, refund_id, sharing_detail_id),
    unique (out_return_no),
    foreign key (tenant_id, refund_id) references refund_transaction(tenant_id, id),
    foreign key (tenant_id, sharing_order_id) references payment_profit_sharing_order(tenant_id, id),
    foreign key (tenant_id, sharing_detail_id) references payment_profit_sharing_detail(tenant_id, id)
);

create index payment_profit_sharing_dispatch_idx
    on payment_profit_sharing_order (next_attempt_at)
    where status in ('PLANNED', 'PROCESSING', 'FAILED');
create index payment_profit_sharing_return_dispatch_idx
    on payment_profit_sharing_return (next_attempt_at)
    where status in ('PLANNED', 'PROCESSING', 'FAILED');

do $$
declare
    table_name text;
begin
    foreach table_name in array array[
        'profit_sharing_receiver', 'profit_sharing_policy',
        'payment_profit_sharing_order', 'payment_profit_sharing_detail',
        'payment_profit_sharing_return'
    ] loop
        execute format('alter table %I enable row level security', table_name);
        execute format('alter table %I force row level security', table_name);
        execute format(
            'create policy tenant_isolation on %I using (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) with check (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)',
            table_name
        );
    end loop;
end $$;

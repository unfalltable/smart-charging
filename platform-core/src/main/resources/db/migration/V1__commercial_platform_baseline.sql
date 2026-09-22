create table tenant (
    id uuid primary key,
    code varchar(64) not null unique,
    display_name varchar(160) not null,
    status varchar(24) not null check (status in ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0
);

create table platform_user (
    id uuid primary key,
    subject varchar(160) not null unique,
    mobile_ciphertext text,
    display_name varchar(120),
    status varchar(24) not null check (status in ('ACTIVE', 'LOCKED', 'CLOSED')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0
);

create table tenant_membership (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    user_id uuid not null references platform_user(id),
    role_code varchar(64) not null,
    status varchar(24) not null check (status in ('ACTIVE', 'DISABLED')),
    created_at timestamptz not null default now(),
    unique (tenant_id, user_id, role_code)
);

create table station (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    code varchar(64) not null,
    name varchar(160) not null,
    address varchar(500),
    longitude numeric(10, 7),
    latitude numeric(10, 7),
    timezone varchar(64) not null default 'Asia/Shanghai',
    status varchar(24) not null check (status in ('DRAFT', 'ACTIVE', 'OFFLINE', 'CLOSED')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0,
    unique (tenant_id, id),
    unique (tenant_id, code)
);

create table device (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    station_id uuid not null,
    device_code varchar(96) not null,
    protocol_code varchar(64) not null,
    product_model varchar(96) not null,
    firmware_version varchar(64),
    connector_count smallint not null check (connector_count between 1 and 128),
    status varchar(24) not null check (status in ('PROVISIONING', 'ONLINE', 'OFFLINE', 'FAULTED', 'RETIRED')),
    credential_fingerprint varchar(128),
    last_seen_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0,
    unique (tenant_id, id),
    unique (device_code),
    unique (tenant_id, device_code),
    foreign key (tenant_id, station_id) references station(tenant_id, id)
);

create table device_route (
    device_code varchar(96) primary key,
    tenant_id uuid not null references tenant(id),
    device_id uuid not null unique,
    foreign key (tenant_id, device_id) references device(tenant_id, id) on delete cascade
);

create function maintain_device_route() returns trigger
language plpgsql security definer set search_path = public, pg_temp as $$
begin
    if tg_op = 'DELETE' then
        delete from device_route where device_id = old.id;
        return old;
    end if;
    if tg_op = 'UPDATE' and old.device_code <> new.device_code then
        delete from device_route where device_id = old.id;
    end if;
    insert into device_route (device_code, tenant_id, device_id)
    values (new.device_code, new.tenant_id, new.id)
    on conflict (device_id) do update
        set device_code = excluded.device_code, tenant_id = excluded.tenant_id;
    return new;
end $$;

create trigger device_route_sync
after insert or update of device_code, tenant_id or delete on device
for each row execute function maintain_device_route();

create table connector (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    device_id uuid not null,
    connector_no smallint not null check (connector_no > 0),
    external_code varchar(96) not null,
    rated_power_w integer check (rated_power_w > 0),
    status varchar(24) not null check (status in ('AVAILABLE', 'RESERVED', 'CHARGING', 'FAULTED', 'DISABLED', 'OFFLINE')),
    last_status_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0,
    unique (tenant_id, id),
    unique (device_id, connector_no),
    unique (tenant_id, external_code),
    foreign key (tenant_id, device_id) references device(tenant_id, id)
);

create table customer (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    status varchar(24) not null check (status in ('ACTIVE', 'BLOCKED', 'CLOSED')),
    display_name varchar(120),
    mobile_ciphertext text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0,
    unique (tenant_id, id)
);

create table customer_identity (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    customer_id uuid not null,
    provider varchar(24) not null check (provider in ('WECHAT', 'ALIPAY', 'MOBILE')),
    provider_subject varchar(160) not null,
    union_subject varchar(160),
    created_at timestamptz not null default now(),
    unique (tenant_id, provider, provider_subject),
    foreign key (tenant_id, customer_id) references customer(tenant_id, id)
);

create table tariff (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    name varchar(160) not null,
    currency char(3) not null default 'CNY',
    billing_mode varchar(24) not null check (billing_mode in ('DURATION', 'ENERGY', 'HYBRID')),
    price_rules jsonb not null,
    effective_from timestamptz not null,
    effective_until timestamptz,
    status varchar(24) not null check (status in ('DRAFT', 'ACTIVE', 'EXPIRED')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0,
    unique (tenant_id, id),
    check (effective_until is null or effective_until > effective_from)
);

alter table connector add column tariff_id uuid;
alter table connector add constraint connector_tariff_fk
    foreign key (tenant_id, tariff_id) references tariff(tenant_id, id);

create table charging_order (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    order_no varchar(40) not null,
    customer_id uuid not null,
    connector_id uuid not null,
    tariff_id uuid not null,
    status varchar(24) not null check (status in ('CREATED', 'START_PENDING', 'CHARGING', 'STOP_PENDING', 'COMPLETED', 'CANCELLED', 'FAILED')),
    idempotency_key varchar(128) not null,
    currency char(3) not null default 'CNY',
    estimated_amount_minor bigint not null default 0 check (estimated_amount_minor >= 0),
    payable_amount_minor bigint not null default 0 check (payable_amount_minor >= 0),
    paid_amount_minor bigint not null default 0 check (paid_amount_minor >= 0),
    started_at timestamptz,
    stopped_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0,
    unique (tenant_id, id),
    unique (tenant_id, order_no),
    unique (tenant_id, idempotency_key),
    check (stopped_at is null or started_at is null or stopped_at >= started_at),
    foreign key (tenant_id, customer_id) references customer(tenant_id, id),
    foreign key (tenant_id, connector_id) references connector(tenant_id, id),
    foreign key (tenant_id, tariff_id) references tariff(tenant_id, id)
);

create table charging_session (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    order_id uuid not null unique,
    device_session_id varchar(128),
    meter_start_wh bigint check (meter_start_wh >= 0),
    meter_stop_wh bigint check (meter_stop_wh >= 0),
    energy_wh bigint not null default 0 check (energy_wh >= 0),
    started_at timestamptz,
    stopped_at timestamptz,
    stop_reason varchar(64),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0,
    unique (tenant_id, id),
    foreign key (tenant_id, order_id) references charging_order(tenant_id, id)
);

create table meter_sample (
    tenant_id uuid not null references tenant(id),
    device_id uuid not null,
    connector_id uuid not null,
    session_id uuid,
    sampled_at timestamptz not null,
    sequence_no bigint not null,
    energy_wh bigint not null check (energy_wh >= 0),
    power_w integer,
    voltage_mv integer,
    current_ma integer,
    raw_payload jsonb,
    primary key (tenant_id, device_id, sampled_at, sequence_no),
    foreign key (tenant_id, device_id) references device(tenant_id, id),
    foreign key (tenant_id, connector_id) references connector(tenant_id, id),
    foreign key (tenant_id, session_id) references charging_session(tenant_id, id)
);

create index meter_sample_session_time_idx on meter_sample (session_id, sampled_at);
create index meter_sample_device_time_idx on meter_sample (device_id, sampled_at desc);

create table device_message (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    device_id uuid not null,
    message_id uuid not null,
    nonce varchar(160) not null,
    event_type varchar(48) not null,
    occurred_at timestamptz not null,
    received_at timestamptz not null default now(),
    payload jsonb not null,
    unique (device_id, message_id),
    unique (device_id, nonce),
    foreign key (tenant_id, device_id) references device(tenant_id, id)
);

create table device_command (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    device_id uuid not null,
    connector_id uuid,
    order_id uuid,
    command_type varchar(48) not null,
    status varchar(24) not null check (status in ('PENDING', 'PUBLISHED', 'ACKNOWLEDGED', 'FAILED', 'EXPIRED')),
    payload jsonb not null,
    expires_at timestamptz not null,
    published_at timestamptz,
    acknowledged_at timestamptz,
    failure_code varchar(96),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0,
    foreign key (tenant_id, device_id) references device(tenant_id, id),
    foreign key (tenant_id, connector_id) references connector(tenant_id, id),
    foreign key (tenant_id, order_id) references charging_order(tenant_id, id)
);

create index device_command_dispatch_idx on device_command (status, expires_at) where status in ('PENDING', 'PUBLISHED');
create unique index device_command_active_stop_idx on device_command (tenant_id, order_id, command_type)
    where command_type = 'STOP_CHARGING' and status in ('PENDING', 'PUBLISHED', 'ACKNOWLEDGED');

create table payment_transaction (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    order_id uuid not null,
    channel varchar(24) not null check (channel in ('WECHAT', 'ALIPAY', 'BALANCE')),
    transaction_type varchar(24) not null check (transaction_type in ('PREAUTHORIZE', 'PAY', 'CAPTURE', 'CLOSE')),
    merchant_order_no varchar(64) not null,
    provider_transaction_no varchar(128),
    amount_minor bigint not null check (amount_minor >= 0),
    currency char(3) not null default 'CNY',
    status varchar(24) not null check (status in ('CREATED', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'CLOSED')),
    request_payload jsonb,
    response_payload jsonb,
    completed_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (tenant_id, id),
    unique (tenant_id, merchant_order_no),
    unique (channel, provider_transaction_no),
    foreign key (tenant_id, order_id) references charging_order(tenant_id, id)
);

create table refund_transaction (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    payment_id uuid not null,
    merchant_refund_no varchar(64) not null,
    provider_refund_no varchar(128),
    amount_minor bigint not null check (amount_minor > 0),
    status varchar(24) not null check (status in ('CREATED', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'CLOSED')),
    reason varchar(500),
    completed_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (tenant_id, merchant_refund_no),
    foreign key (tenant_id, payment_id) references payment_transaction(tenant_id, id)
);

create table ledger_account (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    account_code varchar(64) not null,
    account_type varchar(32) not null,
    currency char(3) not null default 'CNY',
    status varchar(24) not null check (status in ('ACTIVE', 'CLOSED')),
    created_at timestamptz not null default now(),
    unique (tenant_id, id),
    unique (tenant_id, account_code, currency)
);

create table ledger_transaction (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    reference_type varchar(48) not null,
    reference_id uuid not null,
    description varchar(300),
    occurred_at timestamptz not null,
    created_at timestamptz not null default now(),
    unique (tenant_id, id),
    unique (tenant_id, reference_type, reference_id)
);

create table ledger_entry (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    transaction_id uuid not null,
    account_id uuid not null,
    direction varchar(8) not null check (direction in ('DEBIT', 'CREDIT')),
    amount_minor bigint not null check (amount_minor > 0),
    currency char(3) not null,
    created_at timestamptz not null default now(),
    foreign key (tenant_id, transaction_id) references ledger_transaction(tenant_id, id),
    foreign key (tenant_id, account_id) references ledger_account(tenant_id, id)
);

create table idempotency_record (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    scope varchar(96) not null,
    idempotency_key varchar(160) not null,
    request_hash varchar(128) not null,
    response_status integer,
    response_body jsonb,
    expires_at timestamptz not null,
    created_at timestamptz not null default now(),
    unique (tenant_id, scope, idempotency_key)
);

create table outbox_event (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    aggregate_type varchar(96) not null,
    aggregate_id uuid not null,
    event_type varchar(160) not null,
    payload jsonb not null,
    occurred_at timestamptz not null,
    available_at timestamptz not null default now(),
    published_at timestamptz,
    attempts integer not null default 0,
    last_error text
);

create index outbox_unpublished_idx on outbox_event (available_at, occurred_at) where published_at is null;

create table inbox_message (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    consumer_name varchar(96) not null,
    message_id varchar(160) not null,
    received_at timestamptz not null default now(),
    processed_at timestamptz,
    unique (tenant_id, consumer_name, message_id)
);

create table audit_log (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    actor_subject varchar(160) not null,
    action varchar(160) not null,
    resource_type varchar(96) not null,
    resource_id varchar(160),
    request_id varchar(96),
    source_ip inet,
    before_data jsonb,
    after_data jsonb,
    occurred_at timestamptz not null default now()
);

create index audit_log_tenant_time_idx on audit_log (tenant_id, occurred_at desc);
create index charging_order_customer_time_idx on charging_order (tenant_id, customer_id, created_at desc);
create index charging_order_connector_active_idx on charging_order (connector_id, status)
    where status in ('START_PENDING', 'CHARGING', 'STOP_PENDING');

do $$
declare
    table_name text;
begin
    foreach table_name in array array[
        'tenant_membership', 'station', 'device', 'connector', 'customer', 'customer_identity',
        'tariff', 'charging_order', 'charging_session', 'meter_sample', 'device_message',
        'device_command', 'payment_transaction', 'refund_transaction', 'ledger_account',
        'ledger_transaction', 'ledger_entry', 'idempotency_record', 'outbox_event',
        'inbox_message', 'audit_log'
    ] loop
        execute format('alter table %I enable row level security', table_name);
        execute format('alter table %I force row level security', table_name);
        execute format(
            'create policy tenant_isolation on %I using (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) with check (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)',
            table_name
        );
    end loop;
end $$;

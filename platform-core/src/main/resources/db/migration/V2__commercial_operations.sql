create table order_status_history (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    order_id uuid not null,
    from_status varchar(24),
    to_status varchar(24) not null,
    reason varchar(160),
    actor_subject varchar(160) not null,
    occurred_at timestamptz not null default now(),
    foreign key (tenant_id, order_id) references charging_order(tenant_id, id)
);

create index order_status_history_order_idx
    on order_status_history (tenant_id, order_id, occurred_at);

create table merchant_channel (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    channel varchar(24) not null check (channel in ('WECHAT', 'ALIPAY')),
    merchant_id varchar(128) not null,
    application_id varchar(128) not null,
    secret_reference varchar(300) not null,
    notify_url varchar(500) not null,
    refund_notify_url varchar(500) not null,
    status varchar(24) not null check (status in ('DISABLED', 'TESTING', 'ACTIVE')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0,
    unique (tenant_id, id),
    unique (tenant_id, channel, merchant_id)
);

create table payment_route (
    merchant_order_no varchar(64) primary key,
    tenant_id uuid not null references tenant(id),
    payment_id uuid not null unique,
    foreign key (tenant_id, payment_id) references payment_transaction(tenant_id, id) on delete cascade
);

create table payment_webhook (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    payment_id uuid not null,
    channel varchar(24) not null check (channel in ('WECHAT', 'ALIPAY')),
    provider_event_id varchar(160) not null,
    signature_valid boolean not null,
    payload jsonb not null,
    received_at timestamptz not null default now(),
    processed_at timestamptz,
    unique (channel, provider_event_id),
    foreign key (tenant_id, payment_id) references payment_transaction(tenant_id, id)
);

create table reconciliation_batch (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    channel varchar(24) not null check (channel in ('WECHAT', 'ALIPAY', 'BALANCE')),
    statement_date date not null,
    status varchar(24) not null check (status in ('IMPORTED', 'PROCESSING', 'MATCHED', 'EXCEPTION', 'CLOSED')),
    source_file_hash varchar(128) not null,
    total_count integer not null default 0 check (total_count >= 0),
    matched_count integer not null default 0 check (matched_count >= 0),
    exception_count integer not null default 0 check (exception_count >= 0),
    created_at timestamptz not null default now(),
    completed_at timestamptz,
    unique (tenant_id, id),
    unique (tenant_id, channel, statement_date, source_file_hash)
);

create table reconciliation_item (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    batch_id uuid not null,
    payment_id uuid,
    merchant_order_no varchar(64) not null,
    provider_transaction_no varchar(128),
    provider_amount_minor bigint not null check (provider_amount_minor >= 0),
    platform_amount_minor bigint check (platform_amount_minor >= 0),
    result varchar(24) not null check (result in ('MATCHED', 'MISSING_PLATFORM', 'MISSING_PROVIDER', 'AMOUNT_MISMATCH')),
    detail varchar(500),
    created_at timestamptz not null default now(),
    foreign key (tenant_id, batch_id) references reconciliation_batch(tenant_id, id),
    foreign key (tenant_id, payment_id) references payment_transaction(tenant_id, id)
);

create index reconciliation_item_exception_idx
    on reconciliation_item (tenant_id, batch_id, result) where result <> 'MATCHED';

create table settlement_rule (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    name varchar(160) not null,
    beneficiary_code varchar(96) not null,
    share_basis_points integer not null check (share_basis_points between 0 and 10000),
    effective_from date not null,
    effective_until date,
    status varchar(24) not null check (status in ('DRAFT', 'ACTIVE', 'EXPIRED')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (tenant_id, id),
    check (effective_until is null or effective_until >= effective_from)
);

create table settlement_statement (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    rule_id uuid not null,
    period_start date not null,
    period_end date not null,
    gross_amount_minor bigint not null check (gross_amount_minor >= 0),
    settlement_amount_minor bigint not null check (settlement_amount_minor >= 0),
    status varchar(24) not null check (status in ('DRAFT', 'CONFIRMED', 'PAYING', 'PAID', 'CANCELLED')),
    confirmed_at timestamptz,
    paid_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (tenant_id, id),
    unique (tenant_id, rule_id, period_start, period_end),
    check (period_end >= period_start),
    foreign key (tenant_id, rule_id) references settlement_rule(tenant_id, id)
);

create table wallet_account (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    customer_id uuid not null,
    currency char(3) not null default 'CNY',
    balance_minor bigint not null default 0 check (balance_minor >= 0),
    frozen_minor bigint not null default 0 check (frozen_minor >= 0),
    status varchar(24) not null check (status in ('ACTIVE', 'FROZEN', 'CLOSED')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0,
    unique (tenant_id, id),
    unique (tenant_id, customer_id, currency),
    foreign key (tenant_id, customer_id) references customer(tenant_id, id)
);

create table wallet_entry (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    wallet_id uuid not null,
    reference_type varchar(48) not null,
    reference_id uuid not null,
    direction varchar(8) not null check (direction in ('CREDIT', 'DEBIT')),
    amount_minor bigint not null check (amount_minor > 0),
    balance_after_minor bigint not null check (balance_after_minor >= 0),
    occurred_at timestamptz not null default now(),
    unique (tenant_id, wallet_id, reference_type, reference_id, direction),
    foreign key (tenant_id, wallet_id) references wallet_account(tenant_id, id)
);

create table invoice_request (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    customer_id uuid not null,
    order_id uuid not null,
    title varchar(200) not null,
    tax_number varchar(64),
    email varchar(254) not null,
    amount_minor bigint not null check (amount_minor > 0),
    status varchar(24) not null check (status in ('SUBMITTED', 'PROCESSING', 'ISSUED', 'REJECTED', 'RED_ISSUED')),
    invoice_url varchar(500),
    credit_note_url varchar(500),
    issued_at timestamptz,
    red_issued_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (tenant_id, order_id),
    foreign key (tenant_id, customer_id) references customer(tenant_id, id),
    foreign key (tenant_id, order_id) references charging_order(tenant_id, id)
);

create table device_alarm (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    device_id uuid not null,
    connector_id uuid,
    external_alarm_id varchar(160) not null,
    alarm_code varchar(96) not null,
    severity varchar(16) not null check (severity in ('INFO', 'WARNING', 'CRITICAL')),
    message varchar(500) not null,
    status varchar(24) not null check (status in ('OPEN', 'ACKNOWLEDGED', 'RESOLVED')),
    occurred_at timestamptz not null,
    acknowledged_at timestamptz,
    resolved_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (tenant_id, id),
    unique (tenant_id, device_id, external_alarm_id),
    foreign key (tenant_id, device_id) references device(tenant_id, id),
    foreign key (tenant_id, connector_id) references connector(tenant_id, id)
);

create index device_alarm_open_idx on device_alarm (tenant_id, severity, occurred_at desc)
    where status in ('OPEN', 'ACKNOWLEDGED');

create table work_order (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    work_order_no varchar(48) not null,
    alarm_id uuid,
    device_id uuid,
    connector_id uuid,
    customer_id uuid,
    title varchar(200) not null,
    description varchar(1000),
    priority varchar(16) not null check (priority in ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    status varchar(24) not null check (status in ('OPEN', 'ASSIGNED', 'IN_PROGRESS', 'RESOLVED', 'CLOSED', 'CANCELLED')),
    assignee_subject varchar(160),
    due_at timestamptz,
    resolved_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0,
    unique (tenant_id, id),
    unique (tenant_id, work_order_no),
    foreign key (tenant_id, alarm_id) references device_alarm(tenant_id, id),
    foreign key (tenant_id, device_id) references device(tenant_id, id),
    foreign key (tenant_id, connector_id) references connector(tenant_id, id),
    foreign key (tenant_id, customer_id) references customer(tenant_id, id)
);

create table agreement_document (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    document_code varchar(64) not null,
    version varchar(32) not null,
    title varchar(200) not null,
    content_url varchar(500) not null,
    content_hash varchar(128) not null,
    status varchar(24) not null check (status in ('DRAFT', 'ACTIVE', 'EXPIRED')),
    effective_at timestamptz not null,
    created_at timestamptz not null default now(),
    unique (tenant_id, id),
    unique (tenant_id, document_code, version)
);

create table customer_agreement (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    customer_id uuid not null,
    document_id uuid not null,
    accepted_at timestamptz not null default now(),
    source varchar(24) not null check (source in ('WECHAT', 'ALIPAY', 'WEB')),
    unique (tenant_id, customer_id, document_id),
    foreign key (tenant_id, customer_id) references customer(tenant_id, id),
    foreign key (tenant_id, document_id) references agreement_document(tenant_id, id)
);

create table auth_refresh_token (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    customer_id uuid not null,
    token_hash varchar(128) not null unique,
    expires_at timestamptz not null,
    revoked_at timestamptz,
    replaced_by uuid,
    created_at timestamptz not null default now(),
    last_used_at timestamptz,
    unique (tenant_id, id),
    foreign key (tenant_id, customer_id) references customer(tenant_id, id),
    foreign key (tenant_id, replaced_by) references auth_refresh_token(tenant_id, id)
);

create index auth_refresh_token_active_idx on auth_refresh_token (token_hash, expires_at)
    where revoked_at is null;

create table notification_outbox (
    id uuid primary key,
    tenant_id uuid not null references tenant(id),
    customer_id uuid,
    channel varchar(24) not null check (channel in ('WECHAT', 'ALIPAY', 'SMS', 'EMAIL', 'WEBHOOK')),
    template_code varchar(96) not null,
    recipient varchar(300),
    payload jsonb not null,
    status varchar(24) not null check (status in ('PENDING', 'SENDING', 'SENT', 'FAILED', 'CANCELLED')),
    attempts integer not null default 0 check (attempts >= 0),
    available_at timestamptz not null default now(),
    sent_at timestamptz,
    last_error varchar(500),
    created_at timestamptz not null default now(),
    foreign key (tenant_id, customer_id) references customer(tenant_id, id)
);

create index notification_pending_idx on notification_outbox (available_at)
    where status in ('PENDING', 'FAILED');

do $$
declare
    table_name text;
begin
    foreach table_name in array array[
        'order_status_history', 'merchant_channel', 'payment_webhook',
        'reconciliation_batch', 'reconciliation_item', 'settlement_rule',
        'settlement_statement', 'wallet_account', 'wallet_entry', 'invoice_request',
        'device_alarm', 'work_order', 'agreement_document', 'customer_agreement',
        'auth_refresh_token', 'notification_outbox'
    ] loop
        execute format('alter table %I enable row level security', table_name);
        execute format('alter table %I force row level security', table_name);
        execute format(
            'create policy tenant_isolation on %I using (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) with check (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)',
            table_name
        );
    end loop;
end $$;

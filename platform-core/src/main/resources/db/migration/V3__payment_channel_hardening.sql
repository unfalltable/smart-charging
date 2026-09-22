create unique index merchant_channel_one_active_idx
    on merchant_channel (tenant_id, channel) where status = 'ACTIVE';

create unique index refund_transaction_provider_idx
    on refund_transaction (tenant_id, provider_refund_no) where provider_refund_no is not null;

create unique index payment_transaction_one_active_order_idx
    on payment_transaction (tenant_id, order_id)
    where transaction_type='PAY' and status in ('CREATED','PROCESSING');

create unique index agreement_document_one_active_code_idx
    on agreement_document (tenant_id, document_code) where status='ACTIVE';

alter table auth_refresh_token add column family_id uuid;
update auth_refresh_token set family_id=id where family_id is null;
alter table auth_refresh_token alter column family_id set not null;
create index auth_refresh_token_family_idx on auth_refresh_token (tenant_id, family_id);

alter table merchant_channel add column organization_id uuid;

update merchant_channel channel
   set organization_id = (
      select id
        from operator_organization
       where tenant_id=channel.tenant_id and hierarchy_level=1
       order by created_at, id
       limit 1
  );

alter table merchant_channel alter column organization_id set not null;
alter table merchant_channel add constraint merchant_channel_organization_fk
    foreign key (tenant_id, organization_id) references operator_organization(tenant_id, id);
drop index if exists merchant_channel_one_active_idx;
create unique index merchant_channel_one_active_org_idx
    on merchant_channel (tenant_id, organization_id, channel) where status='ACTIVE';

alter table payment_transaction add column merchant_channel_id uuid;
update payment_transaction payment
   set merchant_channel_id = (
      select id
        from merchant_channel
       where tenant_id=payment.tenant_id and channel=payment.channel
       order by (status='ACTIVE') desc, created_at desc
       limit 1
  )
 where payment.channel in ('WECHAT', 'ALIPAY');
alter table payment_transaction add constraint payment_transaction_merchant_channel_fk
    foreign key (tenant_id, merchant_channel_id) references merchant_channel(tenant_id, id);
alter table payment_transaction add constraint payment_transaction_merchant_channel_check
    check ((channel='BALANCE' and merchant_channel_id is null)
        or (channel in ('WECHAT','ALIPAY') and merchant_channel_id is not null));

alter table profit_sharing_receiver add column merchant_channel_id uuid;
update profit_sharing_receiver receiver
   set merchant_channel_id = (
      select id
        from merchant_channel
       where tenant_id=receiver.tenant_id and channel=receiver.channel
       order by (status='ACTIVE') desc, created_at desc
       limit 1
  );
alter table profit_sharing_receiver alter column merchant_channel_id set not null;
alter table profit_sharing_receiver add constraint profit_sharing_receiver_merchant_channel_fk
    foreign key (tenant_id, merchant_channel_id) references merchant_channel(tenant_id, id);
alter table profit_sharing_receiver
    drop constraint if exists profit_sharing_receiver_tenant_id_channel_receiver_account_key;
drop index if exists profit_sharing_one_platform_receiver_idx;
drop index if exists profit_sharing_one_org_receiver_idx;
create unique index profit_sharing_receiver_account_idx
    on profit_sharing_receiver (tenant_id, merchant_channel_id, receiver_account);
create unique index profit_sharing_one_platform_receiver_idx
    on profit_sharing_receiver (tenant_id, merchant_channel_id)
    where owner_type='PLATFORM' and status in ('PENDING','ACTIVE');
create unique index profit_sharing_one_org_receiver_idx
    on profit_sharing_receiver (tenant_id, merchant_channel_id, organization_id)
    where owner_type='ORGANIZATION' and status in ('PENDING','ACTIVE');

alter table payment_profit_sharing_order add column merchant_channel_id uuid;
update payment_profit_sharing_order sharing
   set merchant_channel_id = payment.merchant_channel_id
  from payment_transaction payment
 where payment.tenant_id=sharing.tenant_id and payment.id=sharing.payment_id;
alter table payment_profit_sharing_order alter column merchant_channel_id set not null;
alter table payment_profit_sharing_order add constraint payment_profit_sharing_order_merchant_channel_fk
    foreign key (tenant_id, merchant_channel_id) references merchant_channel(tenant_id, id);

create index merchant_channel_org_lookup_idx
    on merchant_channel (tenant_id, organization_id, channel, status);

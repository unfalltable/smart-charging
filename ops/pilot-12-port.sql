begin;

insert into tenant (id, code, display_name, status)
values ('11111111-1111-1111-1111-111111111111', 'pilot', 'Pilot Tenant', 'ACTIVE')
on conflict (id) do nothing;

select set_config('app.tenant_id', '11111111-1111-1111-1111-111111111111', true);

insert into station (id, tenant_id, code, name, address, status)
values (
    '22222222-2222-2222-2222-222222222222',
    '11111111-1111-1111-1111-111111111111',
    'STATION001', '12-Port Pilot Station', 'Pilot environment only', 'ACTIVE'
)
on conflict (tenant_id, code) do nothing;

insert into device (
    id, tenant_id, station_id, device_code, protocol_code, product_model, connector_count, status
)
values (
    '33333333-3333-3333-3333-333333333333',
    '11111111-1111-1111-1111-111111111111',
    '22222222-2222-2222-2222-222222222222',
    'PILE001', 'JSON-LINES-1', 'PILOT-12', 12, 'OFFLINE'
)
on conflict (tenant_id, device_code) do nothing;

insert into device_route (device_code, tenant_id, device_id)
values ('PILE001', '11111111-1111-1111-1111-111111111111', '33333333-3333-3333-3333-333333333333')
on conflict (device_code) do update
set tenant_id = excluded.tenant_id, device_id = excluded.device_id;

insert into connector (id, tenant_id, device_id, connector_no, external_code, rated_power_w, status)
select (
           '44444444-4444-4444-4444-' || lpad(port_no::text, 12, '0')
       )::uuid,
       '11111111-1111-1111-1111-111111111111',
       '33333333-3333-3333-3333-333333333333',
       port_no,
       'PILE001-' || lpad(port_no::text, 2, '0'),
       800,
       'AVAILABLE'
  from generate_series(1, 12) as port_no
on conflict (device_id, connector_no) do nothing;

insert into customer (id, tenant_id, status, display_name)
values (
    '55555555-5555-5555-5555-555555555555',
    '11111111-1111-1111-1111-111111111111',
    'ACTIVE', 'Pilot Customer'
)
on conflict (id) do nothing;

insert into tariff (
    id, tenant_id, name, billing_mode, price_rules, effective_from, status
)
values (
    '66666666-6666-6666-6666-666666666666',
    '11111111-1111-1111-1111-111111111111',
    'Pilot Duration Tariff', 'DURATION',
    '{"unit":"MINUTE","unitPriceMinor":1,"minimumAmountMinor":1}'::jsonb,
    '2026-01-01T00:00:00Z', 'ACTIVE'
)
on conflict (id) do nothing;

update connector
   set tariff_id = '66666666-6666-6666-6666-666666666666'
 where tenant_id = '11111111-1111-1111-1111-111111111111'
   and device_id = '33333333-3333-3333-3333-333333333333';

commit;

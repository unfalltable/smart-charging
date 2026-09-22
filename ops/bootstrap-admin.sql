\set ON_ERROR_STOP on

begin;

select id::text as bootstrap_tenant_id
  from tenant
 where code=:'tenant_code' and status='ACTIVE'
\gset

select set_config('app.tenant_id', :'bootstrap_tenant_id', true);

with selected_user as (
    insert into platform_user (id, subject, display_name, status)
    values (gen_random_uuid(), :'admin_subject', :'display_name', 'ACTIVE')
    on conflict (subject) do update
       set display_name=excluded.display_name, status='ACTIVE', updated_at=now()
    returning id
)
insert into tenant_membership (id, tenant_id, user_id, role_code, status)
select gen_random_uuid(), :'bootstrap_tenant_id'::uuid, id, 'TENANT_ADMIN', 'ACTIVE'
  from selected_user
on conflict (tenant_id, user_id, role_code) do update set status='ACTIVE';

commit;

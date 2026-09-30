\set ON_ERROR_STOP on
\getenv migration_password DATABASE_MIGRATION_PASSWORD
\getenv runtime_password DATABASE_RUNTIME_PASSWORD

-- The bootstrap administrator is confined to this short-lived container.
-- Migrations require BYPASSRLS for cross-tenant historical data migrations.
select format('create role charging_migrator login nosuperuser nocreatedb nocreaterole noreplication bypassrls password %L', :'migration_password')
 where not exists (select 1 from pg_roles where rolname = 'charging_migrator') \gexec
select format('alter role charging_migrator login nosuperuser nocreatedb nocreaterole noreplication bypassrls password %L', :'migration_password') \gexec
select format('create role charging_runtime login nosuperuser nocreatedb nocreaterole noreplication nobypassrls password %L', :'runtime_password')
 where not exists (select 1 from pg_roles where rolname = 'charging_runtime') \gexec
select format('alter role charging_runtime login nosuperuser nocreatedb nocreaterole noreplication nobypassrls password %L', :'runtime_password') \gexec

grant connect on database charging to charging_migrator, charging_runtime;
revoke create on schema public from public;
alter schema public owner to charging_migrator;
grant usage on schema public to charging_runtime;

-- Upgrade legacy volumes without replacing data or touching system catalogs.
do $$
declare
    item record;
begin
    -- This managed runtime identity must never inherit or SET ROLE to a
    -- privileged identity left behind by a historical/manual deployment.
    for item in
        select distinct r.rolname, grantor_role.rolname as grantor_name from pg_auth_members m
          join pg_roles r on r.oid = m.roleid
          join pg_roles member_role on member_role.oid = m.member
          join pg_roles grantor_role on grantor_role.oid = m.grantor
         where member_role.rolname = 'charging_runtime'
    loop
        execute format('revoke %I from charging_runtime granted by %I cascade', item.rolname, item.grantor_name);
    end loop;
    for item in
        select c.relname, c.relkind from pg_class c
          join pg_namespace n on n.oid = c.relnamespace
         where n.nspname = 'public' and c.relkind in ('r', 'p', 'v', 'm', 'f')
    loop
        execute format('alter table public.%I owner to charging_migrator', item.relname);
    end loop;
    for item in
        select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
         where n.nspname = 'public' and c.relkind = 'S'
    loop
        execute format('alter sequence public.%I owner to charging_migrator', item.relname);
    end loop;
    for item in
        select p.oid::regprocedure as signature from pg_proc p
          join pg_namespace n on n.oid = p.pronamespace
         where n.nspname = 'public' and p.prokind = 'f'
    loop
        execute format('alter function %s owner to charging_migrator', item.signature);
    end loop;
end $$;

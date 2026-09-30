-- Only the application role receives business-data privileges; migration metadata
-- and schema ownership remain with the migration/bootstrap identities.
do $$
declare resource record;
begin
    if exists(select 1 from pg_roles where rolname='charging_runtime') then
        grant usage on schema public to charging_runtime;
        for resource in select tablename from pg_tables
                         where schemaname='public' and tablename<>'flyway_schema_history' loop
            execute format('grant select, insert, update, delete on table public.%I to charging_runtime', resource.tablename);
        end loop;
        grant usage, select on all sequences in schema public to charging_runtime;
        revoke all on table public.flyway_schema_history from charging_runtime;
    end if;
end $$;

-- The administrator model is now assignment-based rather than invitation-based.
-- Existing active memberships remain usable after an upgrade; obsolete invitation
-- timestamps are retained only for backward-compatible schema migration.
alter table tenant_membership no force row level security;

update tenant_membership
   set accepted_at = coalesce(accepted_at, now()),
       invite_expires_at = null,
       updated_at = now(),
       version = version + 1
 where status = 'ACTIVE'
   and (accepted_at is null or invite_expires_at is not null);

with ranked as (
    select id,
           row_number() over (
               partition by user_id
               order by case when status='ACTIVE' then 0 else 1 end, created_at, id
           ) position
      from tenant_membership
)
delete from tenant_membership
 where id in (select id from ranked where position > 1);

alter table tenant_membership force row level security;

create unique index tenant_membership_single_assignment_per_user
    on tenant_membership (user_id);

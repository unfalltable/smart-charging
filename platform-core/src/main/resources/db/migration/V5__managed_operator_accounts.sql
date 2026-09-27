alter table platform_user
    add column username varchar(160),
    add column email varchar(254),
    add column identity_managed boolean not null default false,
    add column mfa_required boolean not null default false,
    add column last_login_at timestamptz;

create unique index platform_user_username_unique
    on platform_user (lower(username)) where username is not null;

create unique index platform_user_email_unique
    on platform_user (lower(email)) where email is not null;

alter table tenant_membership
    add column invited_at timestamptz not null default now(),
    add column invite_expires_at timestamptz,
    add column accepted_at timestamptz,
    add column invited_by varchar(160),
    add column updated_at timestamptz not null default now(),
    add column version bigint not null default 0,
    add constraint tenant_membership_invitation_window_check
        check (invite_expires_at is null or invite_expires_at > invited_at);

update tenant_membership
   set accepted_at = created_at
 where accepted_at is null;

create index tenant_membership_active_expiry_idx
    on tenant_membership (tenant_id, status, invite_expires_at);

create index tenant_membership_user_idx
    on tenant_membership (user_id, status);

create index admin_refresh_token_expiry_idx on admin_refresh_token (expires_at);
create index admin_login_event_time_idx on admin_login_event (occurred_at);
create index auth_refresh_token_expiry_idx on auth_refresh_token (tenant_id, expires_at);

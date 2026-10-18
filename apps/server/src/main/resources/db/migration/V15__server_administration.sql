-- Preview proposal: one installation, one organisation, one accountable server administrator.
-- This authority is separate from memberships and grants no access to private work or approvals.
-- The existing owner keeps their organisation and also becomes the initial server administrator.
create table server_installation (
	id smallint primary key check (id = 1),
	org_id text not null unique references organizations (id),
	administrator_user_id text not null references users (id),
	initialized_at timestamptz,
	initialized_by text references users (id),
	check ((initialized_at is null) = (initialized_by is null))
);

insert into server_installation (id, org_id, administrator_user_id)
select 1, id, owner_user_id from organizations where id = 'org_owner';

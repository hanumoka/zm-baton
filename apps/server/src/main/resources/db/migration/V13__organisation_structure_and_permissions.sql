-- Stage 1, last required slice: the organisation's structure (FN-0101), one place that judges who may
-- do what (FN-0103), the one-person organisation (FN-0104) and a manager record per person (FN-0402).
-- Names follow the data model's organizations, users, memberships, managers and the sharing scope of
-- documents (G06.1); tables and columns the data model does not have are marked "beyond the data model".
--
-- Every existing row stays and is tied to the one-person organisation `org_owner` and its person
-- `usr_owner`, who gets every role: what the owner could do before, the owner can still do. Nothing is
-- dropped and no existing value changes, except that rows gain the organisation, the person's manager
-- and a sharing scope that keeps them visible as before (`org`; a session's unshared summary `personal`).
-- `add column if not exists` keeps this file working when another migration of the same stage adds the
-- same column first.

-- ------------------------------------------------------------------ people and the organisation

-- V8 made the person; make sure of it before anything points at it.
insert into users (id, display_name) values ('usr_owner', 'owner') on conflict (id) do nothing;

-- Beyond the data model: the person who owns the organisation. Stage 1 approval rule
-- `stage1.owner_approves` (ApprovalPolicy): the organisation's owner approves.
alter table organizations add column if not exists owner_user_id text references users (id);
update organizations set owner_user_id = 'usr_owner' where id = 'org_owner' and owner_user_id is null;

-- Anyone an existing row names as a person but who is not in users yet becomes an inactive person with
-- no role, so every row keeps its person and nobody gains a permission. On the owner's own server
-- every such column holds `usr_owner`, so this adds nobody.
insert into users (id, display_name, status)
select p, p, 'inactive' from (
	select responsible_user_id as p from work_items
	union select user_id from devices
	union select user_id from agent_sessions
	union select owner_user_id from documents
	union select owner_user_id from ai_accounts
	union select user_id from inbox_items
	union select recipient_user_id from review_items
	union select granted_by_user_id from delegations
) named where p is not null
on conflict (id) do nothing;

-- Data model "memberships": a person's roles in an organisation (G02.1, G02.5). The roles decide what a
-- person may do (PermissionRules); disciplines only shape default views and are never checked (R55).
create table memberships (
	org_id text not null references organizations (id),
	user_id text not null references users (id),
	roles text[] not null default '{}'
		check (roles <@ array['requester', 'assignee', 'reviewer', 'approver', 'operator', 'master_admin']::text[]),
	disciplines text[] not null default '{}',
	created_at timestamptz not null default now(),
	primary key (org_id, user_id)
);

create index memberships_by_user on memberships (user_id);

-- The owner holds every role; anyone else already named stays a member without a role.
insert into memberships (org_id, user_id, roles)
select 'org_owner', u.id,
       case when u.id = 'usr_owner'
            then array['requester', 'assignee', 'reviewer', 'approver', 'operator', 'master_admin']::text[]
            else '{}'::text[] end
  from users u
on conflict (org_id, user_id) do nothing;

-- Data model "managers": one record per person (DEC-0099 3). Beyond the data model: the id is the
-- person's id with `mgr_` in front of what follows `usr_`, so the owner's manager stays `mgr_owner`,
-- the executor every delegation so far named.
create table managers (
	id text primary key,
	org_id text not null references organizations (id),
	user_id text not null references users (id),
	created_at timestamptz not null default now(),
	unique (org_id, user_id)
);

insert into managers (id, org_id, user_id)
select case when u.id like 'usr\_%' then 'mgr_' || substring(u.id from 5) else 'mgr_' || u.id end, m.org_id, u.id
  from users u join memberships m on m.user_id = u.id
on conflict do nothing;

-- Beyond the data model: a person's own short notes in their manager record. Always personal: only that
-- person reads them, an organisation's operators included (DEC-0103 2), and the export leaves them out.
create table manager_notes (
	-- `mnt_<uuidv7>`.
	id text primary key,
	manager_id text not null references managers (id),
	body text not null check (char_length(body) between 1 and 2000),
	created_at timestamptz not null default now(),
	updated_at timestamptz not null default now()
);

create index manager_notes_by_manager on manager_notes (manager_id, updated_at desc);

-- ------------------------------------------------------------------ the organisation's items (FN-0101)

-- Beyond the data model from here to the links: teams, products, repositories and environments as items
-- of their own, tied to projects by links. Each can be archived; nothing is deleted.
create table teams (
	-- `tem_<uuidv7>`.
	id text primary key,
	org_id text not null references organizations (id),
	name text not null check (char_length(name) between 1 and 100),
	created_at timestamptz not null default now(),
	archived_at timestamptz
);

create table team_members (
	team_id text not null references teams (id),
	user_id text not null references users (id),
	created_at timestamptz not null default now(),
	primary key (team_id, user_id)
);

create index team_members_by_user on team_members (user_id);

create table products (
	-- `prd_<uuidv7>`.
	id text primary key,
	org_id text not null references organizations (id),
	name text not null check (char_length(name) between 1 and 100),
	created_at timestamptz not null default now(),
	archived_at timestamptz
);

-- The organisation's repositories, as against a device's (device_repositories): the same repository key
-- (`git:<root commit>`) ties them, and a device's sessions and runs to them. Never an absolute path.
create table repositories (
	-- `rep_<uuidv7>`.
	id text primary key,
	org_id text not null references organizations (id),
	repo_key text not null,
	name text not null check (char_length(name) between 1 and 100),
	remote text,
	created_at timestamptz not null default now(),
	archived_at timestamptz,
	unique (org_id, repo_key)
);

create table environments (
	-- `env_<uuidv7>`.
	id text primary key,
	org_id text not null references organizations (id),
	name text not null check (char_length(name) between 1 and 100),
	kind text not null default 'other' check (kind in ('development', 'staging', 'production', 'other')),
	created_at timestamptz not null default now(),
	archived_at timestamptz
);

-- How the items hang together: a team takes on a project, a product is made of projects, a project
-- works in repositories and runs in environments. The kinds say which table each id is in.
create table org_links (
	org_id text not null references organizations (id),
	from_kind text not null,
	from_id text not null,
	to_kind text not null,
	to_id text not null,
	created_at timestamptz not null default now(),
	primary key (from_kind, from_id, to_kind, to_id),
	check ((from_kind, to_kind) in (('team', 'project'), ('product', 'project'), ('project', 'repository'), ('project', 'environment')))
);

create index org_links_to on org_links (to_kind, to_id);

-- The people of a project: who sees what is shared with the project only (`restricted`).
create table project_members (
	project_id text not null references projects (id),
	user_id text not null references users (id),
	created_at timestamptz not null default now(),
	primary key (project_id, user_id)
);

create index project_members_by_user on project_members (user_id);

-- The organisation's repositories from what is already here: a device's repository keeps its name, and
-- the repository work items and decisions were imported from (only zm-docs is imported) is `zm-docs`.
insert into repositories (id, org_id, repo_key, name, remote)
select 'rep_' || md5('org_owner|' || k.repo_key), 'org_owner', k.repo_key, k.name, k.remote
  from (
	select distinct on (repo_key) repo_key, name, remote
	  from (
		select r.repo_key, r.name, r.remote, r.updated_at, 0 as pick from device_repositories r
		union all
		select w.git_ref ->> 'repo_key', 'zm-docs', null, w.updated_at, 1 from work_items w where w.git_ref ->> 'repo_key' is not null
		union all
		select d.git_ref ->> 'repo_key', 'zm-docs', null, d.updated_at, 1 from decisions d where d.git_ref ->> 'repo_key' is not null
	  ) found
	 order by repo_key, pick, updated_at desc
  ) k
on conflict (org_id, repo_key) do nothing;

-- ------------------------------------------------------------------ rows tied to the organisation

-- `org_owner` as the default keeps stage 1's statements and rows written before this migration in the
-- one organisation; the server writes the organisation of every new row itself.
alter table work_items add column if not exists org_id text not null default 'org_owner' references organizations (id);
alter table devices add column if not exists org_id text not null default 'org_owner' references organizations (id);
alter table agent_sessions add column if not exists org_id text not null default 'org_owner' references organizations (id);

-- A work item in a project belongs to the project's organisation (every row here: org_owner).
update work_items w set org_id = p.org_id from projects p where p.id = w.project_id and w.org_id <> p.org_id;
update agent_sessions a set org_id = d.org_id from devices d where d.id = a.device_id and a.org_id <> d.org_id;

create index work_items_by_org on work_items (org_id, updated_at desc);

-- Data model constraint 1: a work item's responsible person is a person (users). Devices and sessions
-- belong to a person too. Every value already is one (see the insert above).
alter table work_items add constraint work_items_responsible_user_fkey foreign key (responsible_user_id) references users (id);
alter table devices add constraint devices_user_fkey foreign key (user_id) references users (id);
alter table agent_sessions add constraint agent_sessions_user_fkey foreign key (user_id) references users (id);

-- Data model "agent_sessions.manager_id": the record a session belongs to.
alter table agent_sessions add column if not exists manager_id text references managers (id);
update agent_sessions a set manager_id = m.id from managers m where m.org_id = a.org_id and m.user_id = a.user_id and a.manager_id is null;

-- ------------------------------------------------------------------ sharing scope (G06.1)

-- personal: the owner only, an operator included (DEC-0103). restricted: the owner and the people of its
-- project. team: those and the teams that take on its project. org: every active member. public: anyone.
-- Everything here was visible to the whole (one-person) organisation, so it starts `org`.
alter table work_items add column if not exists visibility text not null default 'org';
alter table work_items add constraint work_items_visibility_check
	check (visibility in ('personal', 'restricted', 'team', 'org', 'public'));

alter table decisions add column if not exists visibility text not null default 'org';
alter table decisions add constraint decisions_visibility_check
	check (visibility in ('personal', 'restricted', 'team', 'org', 'public'));
-- Beyond the data model: who owns a decision record: who proposed it here, or who imported it.
alter table decisions add column if not exists owner_user_id text references users (id);
update decisions d set owner_user_id = o.owner_user_id from organizations o where o.id = d.org_id and d.owner_user_id is null;

-- documents.visibility (V6) and questions.visibility (V11) were always `org`; now they are checked.
alter table documents add constraint documents_visibility_check
	check (visibility in ('personal', 'restricted', 'team', 'org', 'public'));
alter table questions add constraint questions_visibility_check
	check (visibility in ('personal', 'restricted', 'team', 'org', 'public'));

-- A session's 「지금 하는 일」 is shared with the organisation or kept to its person. The session states
-- it with every summary (share_current_work, contract "「지금 하는 일」" 3); this column only names that
-- choice as a scope, so the two can never disagree.
alter table agent_sessions add column if not exists current_work_visibility text
	generated always as (case when share_current_work then 'org' else 'personal' end) stored;

-- Stage 1: documents (FN-0601 "문서 정본 지정", FN-0602 "문서판과 승인"). Every document has one
-- source of truth (G20.1): `product`, where zm-baton keeps the Markdown body and its versions, or
-- `git`, where a repository file is the source and a version only points at a commit; zm-baton
-- never changes that file. Each version is a draft until its approver approves it.
-- Names follow the data model ("documents", "document_versions", "approvals"); columns it does not
-- have are marked "beyond the data model". Only adds tables and widens one check.

create table documents (
	-- `doc_<uuidv7>` (contract v1 "식별자").
	id text primary key,
	org_id text not null references organizations (id),
	project_id text references projects (id),
	title text not null,
	source_of_truth text not null check (source_of_truth in ('product', 'git')),
	-- Where a Git document lives: `{"repo": "github.com/hanumoka/zm-docs", "path": "docs/a.md", "branch": "main"}`.
	-- A path from the repository root; never an absolute path.
	git_ref jsonb,
	owner_user_id text not null,
	-- Sharing scope (G06.1). Stage 1 has one organisation, so every document is `org`.
	visibility text not null default 'org',
	-- Beyond the data model: the version of the document's own fields (title, project, links,
	-- Git location) a person saw. Adding a document version does not raise it.
	version bigint not null default 0,
	created_at timestamptz not null default now(),
	updated_at timestamptz not null default now(),
	constraint documents_git_has_ref check (source_of_truth = 'product' or git_ref is not null)
);

create index documents_by_project on documents (project_id, updated_at desc);

create table document_versions (
	-- `dvr_<uuidv7>`. Contract v1 has no prefix for document versions; this one keeps them readable.
	id text primary key,
	doc_id text not null references documents (id),
	-- 1, 2, 3 ... within one document.
	number integer not null,
	-- A product document's body (G46.5: Markdown source).
	body_markdown text,
	-- A Git document's version: the commit it points at.
	git_commit_sha text,
	-- Beyond the data model: what changed, in the writer's words.
	note text,
	-- Beyond the data model: who wrote or recorded this version.
	created_by text not null,
	approved_by text,
	approved_at timestamptz,
	created_at timestamptz not null default now(),
	unique (doc_id, number),
	constraint document_versions_has_content check (body_markdown is not null or git_commit_sha is not null),
	constraint document_versions_approved_together check ((approved_by is null) = (approved_at is null))
);

-- Beyond the data model: the work a document affects and the decisions it rests on.
create table document_work_items (
	document_id text not null references documents (id) on delete cascade,
	work_item_id text not null references work_items (id),
	created_at timestamptz not null default now(),
	primary key (document_id, work_item_id)
);

create index document_work_items_by_work on document_work_items (work_item_id);

create table document_decisions (
	document_id text not null references documents (id) on delete cascade,
	decision_id text not null references decisions (id),
	created_at timestamptz not null default now(),
	primary key (document_id, decision_id)
);

-- A document version is approved the same way a decision is (data model "approvals").
alter table approvals drop constraint approvals_target_kind;
alter table approvals add constraint approvals_target_kind check (target_kind in ('decision', 'document_version'));

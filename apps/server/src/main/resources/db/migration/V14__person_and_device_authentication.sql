-- Person and device authentication (stage-1 exit criterion 4, docs/stage1/auth.md).
--
-- Only hashes are kept: a BCrypt hash of a password, the SHA-256 of every token. None of the new
-- tables is exported, and the device token's hash is withheld from the export (ExportService).
-- Nothing existing changes: every table keeps its rows, and a device registered before this version
-- has no token and goes on working while the server stays in local mode (the default).

-- A person's password. The login name is what the person types; it is unique without regard to case.
create table person_passwords (
	user_id text primary key references users (id),
	login text not null check (char_length(login) between 2 and 50),
	password_hash text not null,
	set_at timestamptz not null default now()
);

create unique index person_passwords_login on person_passwords (lower(login));

-- Web logins: the sign-in cookie's token, by its SHA-256. A login unused for 7 days ends, and every
-- login ends 30 days after it began (LoginService).
create table web_logins (
	id text primary key,
	user_id text not null references users (id),
	token_hash text not null unique,
	created_at timestamptz not null default now(),
	last_seen_at timestamptz not null default now(),
	expires_at timestamptz not null,
	ended_at timestamptz
);

create index web_logins_live_by_user on web_logins (user_id) where ended_at is null;

-- One-time setup tokens, written once to the server's log at start (AuthStartup): the first password
-- of an organisation owner, or a reset the server's operator asked for (ZM_BATON_AUTH_SETUP).
create table setup_tokens (
	id text primary key,
	user_id text not null references users (id),
	token_hash text not null unique,
	reason text not null check (reason in ('first_password', 'reset')),
	created_at timestamptz not null default now(),
	expires_at timestamptz not null,
	used_at timestamptz,
	-- A newer token for the same person replaced this one before it was used.
	dropped_at timestamptz
);

-- The device token: `zm-baton login` gets it, the connector and the CLI send it on device calls. Null
-- for a device registered before this version. Revoking a device (revoked_at) stops its token.
alter table devices add column token_hash text unique;
alter table devices add column token_issued_at timestamptz;

-- Device logins, in the shape of RFC 8628 (OAuth 2.0 Device Authorization Grant): on a server in
-- required mode the CLI shows a code, a signed-in person approves it on the Web, and the CLI's next poll
-- registers the device and takes its token. The device code is kept by its SHA-256.
create table device_logins (
	id text primary key,
	device_code_hash text not null unique,
	user_code text not null,
	-- A device that asks for a new token for itself (registered before tokens); null for a new device.
	device_id text references devices (id),
	name text not null,
	os text,
	created_at timestamptz not null default now(),
	expires_at timestamptz not null,
	approved_by text references users (id),
	approved_at timestamptz,
	denied_at timestamptz,
	issued_device_id text references devices (id),
	consumed_at timestamptz,
	last_polled_at timestamptz
);

create unique index device_logins_open_code on device_logins (user_code) where consumed_at is null and denied_at is null;

-- A notice is shared, but each recipient needs an independently addressable inbox row.
-- Notice judgement still closes the shared notice and all its open inbox rows together.
drop index inbox_items_one_open_notice;
create unique index inbox_items_one_open_notice
	on inbox_items (source_id, user_id)
	where state = 'open' and kind = 'notice';

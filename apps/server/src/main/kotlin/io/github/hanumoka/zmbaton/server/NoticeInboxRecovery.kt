package io.github.hanumoka.zmbaton.server

import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** V16 keeps existing inbox ids. Fill missing recipients with the normal UUIDv7 generator. */
@Component
class NoticeInboxRecovery(private val jdbc: JdbcClient) {
	@EventListener(ApplicationReadyEvent::class)
	@Transactional
	fun recover() {
		val missing = jdbc.sql("""
			select distinct on (n.id, recipient) i.id, recipient
			  from notices n join inbox_items i on i.source_id = n.id and i.source_kind = 'ntc'
			 cross join lateral unnest(n.recipients) recipient
			 where n.status = 'open' and i.kind = 'notice' and i.state = 'open'
			   and not exists (select 1 from inbox_items present where present.source_id = n.id
			                   and present.user_id = recipient and present.kind = 'notice' and present.state = 'open')
			 order by n.id, recipient, i.created_at, i.id
		""").query { rs, _ -> rs.getString(1) to rs.getString(2) }.list()
		for ((template, recipient) in missing) {
			// Judgement locks the notice first as well; recovery must not reopen a closed inbox.
			val open = jdbc.sql("select id from notices where id = (select source_id from inbox_items where id = :i) and status = 'open' for update")
				.param("i", template).query(String::class.java).optional().isPresent
			if (!open) continue
			jdbc.sql("""
			insert into inbox_items (id, user_id, bucket, kind, source_kind, source_id, summary, detail,
			                         repeat_count, waiting_since, notify)
			select :id, :recipient, i.bucket, i.kind, i.source_kind, i.source_id, i.summary, i.detail,
			       i.repeat_count, i.waiting_since, i.notify from inbox_items i join notices n on n.id = i.source_id
			 where i.id = :template and i.state = 'open' and n.status = 'open'
			on conflict (source_id, user_id) where state = 'open' and kind = 'notice' do nothing
		""").param("id", Ids.inbox()).param("recipient", recipient).param("template", template).update()
		}
	}
}

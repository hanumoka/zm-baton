import type { Activity, OwnSession } from '../orgApi';

/*
 * What the 「나」 screen shows, as plain functions: the words for what I did, where a record of it
 * opens, and my sessions' states. The server keeps the record (FN-0402); this only names its parts.
 */

export const ACTIONS: Record<string, string> = {
  'work_item.created': '작업을 만들었다',
  'work_item.updated': '작업을 고쳤다',
  'work_item.stage_changed': '작업 단계를 옮겼다',
  'work_item.wait_added': '대기 사유를 달았다',
  'work_item.wait_removed': '대기 사유를 뗐다',
  'work_item.delegated': 'AI에게 맡겼다',
  'work_item.delegation_withdrawn': '맡긴 것을 거뒀다',
  'work_item.run_cancel_requested': '실행을 취소했다',
  'run.input_sent': '실행 중인 AI에게 지시했다',
  'decision.proposed': '결정을 제안했다',
  'decision.updated': '결정을 고쳤다',
  'decision.accepted': '결정을 채택했다',
  'decision.rejected': '결정을 기각했다',
  'document.registered': '문서를 등록했다',
  'document.updated': '문서 정보를 고쳤다',
  'document.version_added': '문서판을 썼다',
  'document.version_approved': '문서판을 승인했다',
  'question.asked': '질문을 남겼다',
  'question.answered': '질문에 답했다',
  'question.withdrawn': '질문을 거뒀다',
  'review.approved': '사람 검토를 승인했다',
  'review.rejected': '사람 검토를 반려했다',
  'relay.answered': '중계된 요청에 답했다',
  'notice.acknowledged': '알림을 확인했다',
  'notice.resolved': '충돌을 해결로 표시했다',
  'notice.not_a_conflict': '충돌 아님으로 표시했다',
  'session.registered': '세션을 등록했다',
  'session.reregistered': '세션을 다시 등록했다',
  'session.ended': '세션이 끝났다',
  'session.deregistered': '세션 등록을 껐다',
  'session.sharing_changed': '「지금 하는 일」 공유를 바꿨다',
  'manager_note.added': '개인 메모를 썼다',
  'manager_note.changed': '개인 메모를 고쳤다',
  'manager_note.deleted': '개인 메모를 지웠다',
  'focus.changed': '집중 모드를 바꿨다',
  'device.registered': '기기를 등록했다',
  'device.token_issued': '기기 토큰을 받았다',
  'device.revoked': '기기를 폐기했다',
  'device_login.approved': '기기 로그인을 허락했다',
  'device_login.denied': '기기 로그인을 거절했다',
  'auth.login': '로그인했다',
  'auth.logout': '로그아웃했다',
  'auth.password_set': '설정 토큰으로 비밀번호를 정했다',
  'auth.password_changed': '비밀번호를 바꿨다',
  'auth.password_change_refused': '비밀번호 바꾸기가 거절됐다',
  'account.registered': 'AI 계정을 등록했다',
  'import.zm_docs.applied': 'zm-docs를 가져왔다',
  'export.downloaded': '내보냈다',
  'import.zm_docs.dry_run': 'zm-docs 가져오기를 미리 봤다',
  'work_item.imported': '작업을 가져왔다',
  'work_item.synced': '가져온 작업을 원본에 맞췄다',
  'decision.imported': '결정을 가져왔다',
  'decision.synced': '가져온 결정을 원본에 맞췄다',
  'project.imported': '프로젝트를 가져왔다',
  'project.synced': '가져온 프로젝트를 원본에 맞췄다',
  'notice.raised': '충돌 알림을 올렸다',
  'notice.reopened': '충돌 알림을 다시 열었다',
  'relay.requested': '권한 요청을 첫 화면으로 보냈다',
  'relay.not_relayed': '권한 요청을 터미널에 남겼다',
  'relay.delivered': '첫 화면의 답을 가져갔다',
  'relay.to_terminal': '중계된 요청을 터미널로 넘겼다',
  'person_action.refused': '사람의 일을 세션 토큰으로 하려다 거절됐다',
  'permission.refused': '권한 판정에서 거절됐다',
  'review_settings.changed': '위험 목록·표본 설정을 바꿨다',
  'workflow.skipped_stages': '건너뛸 검토 단계를 바꿨다',
  'device.repository_added': '기기에 저장소를 더했다',
  'device.repository_removed': '기기에서 저장소를 뺐다',
  'org.renamed': '조직 이름을 바꿨다',
  'org.person_added': '조직에 사람을 더했다',
  'org.person_changed': '사람의 역할·상태를 바꿨다',
  'org.linked': '조직 항목을 이었다',
  'org.unlinked': '조직 항목의 잇기를 끊었다',
};

/** Open work first; what is done or cancelled waits behind its count. */
export function splitWork<T extends { stage: string }>(work: T[]): { open: T[]; closed: T[] } {
  const closed = (w: T) => w.stage === 'done' || w.stage === 'cancelled';
  return { open: work.filter((w) => !closed(w)), closed: work.filter(closed) };
}

export const actionLabel = (action: string) => ACTIONS[action] ?? action;

/** Where a record opens on another screen; null for a record no screen shows. */
export function targetHref(a: Pick<Activity, 'target_kind' | 'target_id'>): string | null {
  if (!a.target_id) return null;
  const screen = { wrk: 'work', dcs: 'decisions', doc: 'documents', ses: 'sessions', acc: 'accounts' }[a.target_kind ?? ''];
  return screen ? `#${screen}?item=${encodeURIComponent(a.target_id)}` : null;
}

export const STATE_LABELS: Record<OwnSession['state'], string> = {
  live: '살아 있음',
  ended: '끝남',
  deregistered: '등록 끔',
  expired: '만료',
};

/** My session's work line: my own words even when they are not shared, and whether they are. */
export function workLine(s: Pick<OwnSession, 'current_work' | 'shared'>): { text: string; private: boolean } {
  return { text: s.current_work ?? '아직 올리지 않았다', private: !s.shared };
}

/** Why a note cannot be saved yet, or null. */
export function noteProblem(body: string): string | null {
  const t = body.trim();
  if (t === '') return '메모를 쓴다';
  if (t.length > 2000) return '메모는 2,000자까지다';
  return null;
}

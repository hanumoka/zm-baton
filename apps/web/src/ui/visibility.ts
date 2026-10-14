/*
 * Sharing scope (G06.1) in words, the one place the screens take them from: work, decisions and
 * documents show it on their fact line, and the organisation screen explains each scope. The server
 * decides who sees what; this only names it.
 */

export const VISIBILITY_LABELS: Record<string, string> = {
  personal: '개인',
  restricted: '제한',
  team: '팀',
  org: '조직',
  public: '공개',
};

/** What each scope lets people see, for the organisation screen. */
export const VISIBILITY_NOTES: Record<string, string> = {
  personal: '본인만 본다. 조직 운영자도 못 본다',
  restricted: '본인, 관계된 사람, 그 프로젝트의 사람만 본다',
  team: '제한에 더해 그 프로젝트를 맡은 팀의 사람이 본다',
  org: '조직에서 활동 중인 사람이 모두 본다',
  public: '누구나 본다',
};

/** The label of a scope; a scope this page does not know shows as the server wrote it. An absent one is `org`, the server's default. */
export function visibilityLabel(v: string | null | undefined): string {
  const key = v ?? 'org';
  return VISIBILITY_LABELS[key] ?? key;
}

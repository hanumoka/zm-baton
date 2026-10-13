import { repoName, shortId, since, stateLabel } from './inboxModel';
import type { SessionView } from './sessionsApi';

type Props = {
  sessions: SessionView[];
  includeEnded: boolean;
  onIncludeEnded: (v: boolean) => void;
  now: number;
};

/** Registered AI sessions and what each works on (contract "직접 연 AI 세션을 서버에 등록한다" 11). */
export function Sessions({ sessions, includeEnded, onIncludeEnded, now }: Props) {
  return (
    <main>
      <header>
        <h1>세션 현황</h1>
        <p>
          등록된 AI 세션과 각자 올린 「지금 하는 일」이다. 등록하지 않았거나 등록을 끈 세션은 여기 없고 충돌 판정에서도 빠진다. 셸 명령으로 바꾼 파일은
          보고되지 않는다.
        </p>
        <label className="inline">
          <input type="checkbox" checked={includeEnded} onChange={(e) => onIncludeEnded(e.target.checked)} /> 끝난 세션도 보기
        </label>
      </header>
      {sessions.length === 0 ? (
        <p className="empty">등록된 세션이 없다. Claude Code를 새로 열면 여기 나타난다.</p>
      ) : (
        <div className="tablewrap">
          <table>
            <thead>
              <tr>
                <th>세션</th>
                <th>실행기</th>
                <th>저장소 · 브랜치</th>
                <th>지금 하는 일</th>
                <th>상태</th>
                <th>최근 고친 파일</th>
              </tr>
            </thead>
            <tbody>
              {sessions.map((s) => (
                <tr key={s.id} className={s.state === 'live' ? undefined : 'gone'}>
                  <td>
                    <code>{shortId(s.id)}</code>
                    <br />
                    <small>{s.device_name}</small>
                  </td>
                  <td>
                    {s.executor}
                    {s.executor_version && <small> {s.executor_version}</small>}
                  </td>
                  <td>
                    {repoName(s)}
                    {s.repository?.branch && <small> · {s.repository.branch}</small>}
                    {s.repository?.prefix && (
                      <>
                        <br />
                        <small>{s.repository.prefix}/</small>
                      </>
                    )}
                  </td>
                  <td>
                    {s.shared ? (s.current_work ?? <small>아직 올리지 않음</small>) : <small>공유 안 함</small>}
                    {s.shared && s.current_work_at && <small> · {since(s.current_work_at, now)}</small>}
                  </td>
                  <td>
                    {stateLabel(s.state)}
                    <br />
                    <small>마지막 신호 {since(s.last_seen_at, now)}</small>
                  </td>
                  <td>
                    {s.touched_files.length === 0 && <small>없음</small>}
                    <ul className="files">
                      {s.touched_files.slice(0, 5).map((f) => (
                        <li key={f} className={s.conflicted_files.includes(f) ? 'conflict' : undefined}>
                          <code>{f}</code>
                          {s.conflicted_files.includes(f) && <b> 겹침</b>}
                        </li>
                      ))}
                    </ul>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </main>
  );
}

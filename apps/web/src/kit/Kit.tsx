import tokensCss from '../tokens.css?raw';
import { Icon, ICONS } from '../icons/Icon';
import { StageGlyph } from '../icons/StageGlyph';
import { EmptyState } from '../ui/EmptyState';
import { GroupHead, GroupSubHead, RowSkeleton } from '../ui/Group';
import { STAGES } from '../workModel';
import { StateGallery } from './States';
import { byPrefix, colourGroups, otherTokens, parseTokens, type Token } from './tokenList';

const TOKENS = parseTokens(tokensCss);
const OPEN_STAGES = STAGES.filter((s) => s.stage !== 'done' && s.stage !== 'cancelled');

function TokenName({ t }: { t: Token }) {
  return (
    <span className="tok">
      <code className="tok-name">{t.name}</code>
      <code className="tok-val">{t.value}</code>
    </span>
  );
}

/**
 * The design kit (opened only by its address, #kit): the token values, every icon, each part in
 * its seven states, the stage glyphs, group heads, empty states and keys. A screen PR checks its
 * parts against this page; a new token or part shows here first.
 */
export function Kit() {
  return (
    <main className="kit">
      <h1>디자인 키트</h1>
      <p className="kit-lead">
        화면이 쓰는 값과 부품이다. 값의 정본은 <code>src/tokens.css</code>이고, 이 화면은 그 파일을 읽어 보인다.
      </p>

      <section aria-labelledby="kit-colour">
        <h2 id="kit-colour">색</h2>
        {colourGroups(TOKENS).map((g) => (
          <div key={g.label}>
            <h3>{g.label}</h3>
            <ul className="swatches">
              {g.tokens.map((t) => (
                <li key={t.name}>
                  <span className="swatch" style={{ background: `var(${t.name})` }} />
                  <TokenName t={t} />
                </li>
              ))}
            </ul>
          </div>
        ))}
      </section>

      <section aria-labelledby="kit-type">
        <h2 id="kit-type">글자 크기</h2>
        <ul className="type-steps">
          {byPrefix(TOKENS, '--fs-').map((t) => (
            <li key={t.name}>
              <TokenName t={t} />
              <span style={{ fontSize: `var(${t.name})` }}>작업을 고르고 다음 단계로 보낸다 TSK-021 14:05</span>
            </li>
          ))}
        </ul>
      </section>

      <section aria-labelledby="kit-space">
        <h2 id="kit-space">간격과 모서리, 그림자</h2>
        <div className="kit-cols">
          <ul className="space-steps">
            {byPrefix(TOKENS, '--s-').map((t) => (
              <li key={t.name}>
                <span className="space-bar" style={{ width: `var(${t.name})` }} />
                <TokenName t={t} />
              </li>
            ))}
          </ul>
          <ul className="radius-steps">
            {[...byPrefix(TOKENS, '--r-'), ...byPrefix(TOKENS, '--sh-')].map((t) => (
              <li key={t.name}>
                <span
                  className="radius-box"
                  style={t.name.startsWith('--sh-') ? { boxShadow: `var(${t.name})` } : { borderRadius: `var(${t.name})` }}
                />
                <TokenName t={t} />
              </li>
            ))}
          </ul>
        </div>
      </section>

      <section aria-labelledby="kit-other">
        <h2 id="kit-other">그 밖의 값</h2>
        <ul className="tok-list">
          {otherTokens(TOKENS).map((t) => (
            <li key={t.name}>
              <TokenName t={t} />
            </li>
          ))}
        </ul>
      </section>

      <section aria-labelledby="kit-icons">
        <h2 id="kit-icons">아이콘 {ICONS.length}개</h2>
        <p className="kit-note">16px 격자, 선 굵기 <code>--stroke</code>, 둥근 선 끝. 늘 글자와 함께 쓴다.</p>
        <ul className="icon-list">
          {ICONS.map((i) => (
            <li key={i.name}>
              <Icon name={i.name} />
              <span>{i.label}</span>
              <code>{i.name}</code>
            </li>
          ))}
        </ul>
      </section>

      <section aria-labelledby="kit-states">
        <h2 id="kit-states">부품의 일곱 상태</h2>
        <p className="kit-note">hover·focus·active는 강제 클래스로 켜 두었다. 누를 수 없는 것은 이유를 함께 보인다.</p>
        <StateGallery />
        <h3>툴팁</h3>
        <p className="tip-demo">
          <button type="button" className="btn tipped is-tip" data-tip="마우스를 대거나 키보드 포커스가 오면 뜬다">
            툴팁이 붙은 단추
          </button>
        </p>
      </section>

      <section aria-labelledby="kit-stages">
        <h2 id="kit-stages">단계 기호</h2>
        <p className="kit-note">나아갈수록 원 안이 아래부터 찬다. 막히면 같은 높이로 찬 네모가 된다. 기호 옆에는 늘 단계 이름을 쓴다.</p>
        <ul className="stage-list">
          {STAGES.map((s) => (
            <li key={s.stage}>
              <StageGlyph stage={s.stage} />
              <span>{s.label}</span>
            </li>
          ))}
        </ul>
        <ul className="stage-list is-block-row">
          {OPEN_STAGES.map((s) => (
            <li key={s.stage}>
              <StageGlyph stage={s.stage} blocked />
              <span>{s.label}, 막힘</span>
            </li>
          ))}
        </ul>
      </section>

      <section aria-labelledby="kit-groups">
        <h2 id="kit-groups">누가 들고 있나 묶음 머리</h2>
        <p className="kit-note">비어 있는 묶음도 보이고 빈 상태 문구를 단다. 이름은 예시다.</p>
        <div className="kit-cols">
          <div className="kit-list" aria-label="1인 조직 예시">
            <p className="kit-list-t">1인 조직</p>
            <GroupHead mark={<StageGlyph stage="in_progress" blocked />} label="기다림" count={2} aside="열린 지" />
            <RowSkeleton rows={2} />
            <GroupHead mark={<Icon name="session" />} label="AI 세션" count={0} />
            <EmptyState kind="empty" title="AI 세션이 든 작업이 없다">
              살아 있는 세션 4개는 아직 작업을 들고 있지 않다.
            </EmptyState>
            <GroupHead mark={<Icon name="person" />} label="사람" count={13} />
            <GroupHead mark={<StageGlyph stage="done" />} label="끝남" count={12} />
          </div>
          <div className="kit-list" aria-label="팀 조직 예시">
            <p className="kit-list-t">팀 조직</p>
            <GroupHead mark={<StageGlyph stage="in_progress" blocked />} label="기다림" count={3} aside="열린 지" />
            <GroupHead mark={<Icon name="session" />} label="AI 세션" count={0} />
            <GroupHead mark={<Icon name="person" />} label="나" count={13} />
            <GroupHead mark={<Icon name="team" />} label="팀원" count={5} />
            <GroupSubHead label="김하늘" count={3} />
            <GroupSubHead label="박도윤" count={2} />
            <GroupHead mark={<StageGlyph stage="done" />} label="끝남" count={12} />
          </div>
        </div>
      </section>

      <section aria-labelledby="kit-empty">
        <h2 id="kit-empty">빈 상태 세 유형</h2>
        <div className="empty-samples">
          <div>
            <h3>아직 안 씀</h3>
            <EmptyState
              kind="unused"
              title="아직 만든 작업이 없다"
              action={
                <button type="button" className="btn btn-primary">
                  <Icon name="plus" />새 작업 만들기
                </button>
              }
            >
              작업은 사람이나 AI가 맡아 끝낼 일 하나다. zm-docs에서 가져올 수도 있다.
            </EmptyState>
          </div>
          <div>
            <h3>지금 비어 있음</h3>
            <EmptyState kind="empty" title="막힌 작업이 없다">
              대기 사유가 붙은 작업이 하나도 없어서 이 묶음이 비었다.
            </EmptyState>
          </div>
          <div>
            <h3>오류</h3>
            <EmptyState
              kind="error"
              title="작업 목록을 읽지 못했다"
              action={
                <button type="button" className="btn">
                  다시 읽기
                </button>
              }
            >
              서버가 응답하지 않는다(HTTP 503). zm-baton 서버를 켠 뒤 다시 읽는다.
            </EmptyState>
          </div>
        </div>
      </section>

      <section aria-labelledby="kit-keys">
        <h2 id="kit-keys">키</h2>
        <ul className="key-list">
          <li>
            <span>
              <kbd>Ctrl</kbd> <kbd>K</kbd>
            </span>
            <span>
              찾기·명령 입력줄로 간다. 맥은 <kbd>Cmd</kbd> <kbd>K</kbd>
            </span>
          </li>
          <li>
            <span>
              <kbd>
                <Icon name="arrow-up" />
                <span className="sr">위</span>
              </kbd>{' '}
              <kbd>
                <Icon name="arrow-down" />
                <span className="sr">아래</span>
              </kbd>
            </span>
            <span>목록에서 옮긴다</span>
          </li>
          <li>
            <span>
              <kbd>Enter</kbd>
            </span>
            <span>고른 것을 연다</span>
          </li>
          <li>
            <span>
              <kbd>Esc</kbd>
            </span>
            <span>시트를 닫거나 목록으로 돌아간다</span>
          </li>
        </ul>
      </section>
    </main>
  );
}

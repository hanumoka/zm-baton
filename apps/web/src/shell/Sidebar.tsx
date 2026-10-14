import { Icon } from '../icons/Icon';
import { FOOT_ITEMS, NAV_GROUPS, screenById, type ScreenId } from './screens';

export type Permission = NotificationPermission | 'unsupported';
export type Counts = Partial<Record<ScreenId, number>>;

const DENIED_FIX = '주소창 왼쪽의 사이트 설정에서 알림을 허용한다';

type Props = {
  current: ScreenId;
  counts: Counts;
  open: boolean;
  onToggle: () => void;
  permission: Permission;
  onAskPermission: () => void;
  /** Focus mode; null until the server said. */
  focus?: boolean | null;
  onToggleFocus?: () => void;
};

/**
 * The left sidebar (DESIGN.md 「화면 배치」 1): a neutral layer below the content, no accent colour.
 * The current screen is marked by a light background and bold letters only. Folded, it shows
 * icons with a name tooltip on hover and on keyboard focus; the names stay for screen readers.
 */
export function Sidebar({ current, counts, open, onToggle, permission, onAskPermission, focus = null, onToggleFocus = () => {} }: Props) {
  const rail = !open;
  const titled = NAV_GROUPS.length > 1 && !rail;
  return (
    <aside id="side" className="side" data-mode={rail ? 'rail' : 'open'} aria-label="주 메뉴">
      <div className="brand">
        <span className="tile" aria-hidden="true">
          z
        </span>
        <span className="brand-nm">zm-baton</span>
      </div>
      <nav className="nav" aria-label="화면">
        {NAV_GROUPS.map((g) => (
          <div key={g.id} className="nav-grp">
            <h2 className={titled ? 'nav-g' : 'nav-g sr'}>{g.label}</h2>
            <ul>
              {g.items.map((id) => (
                <li key={id}>
                  <NavItem id={id} count={counts[id] ?? 0} current={id === current} rail={rail} />
                </li>
              ))}
            </ul>
          </div>
        ))}
      </nav>
      <div className="side-foot">
        <ul>
          {FOOT_ITEMS.map((id) => (
            <li key={id}>
              <NavItem id={id} count={0} current={id === current} rail={rail} />
            </li>
          ))}
          <li>
            <NotifyItem permission={permission} onAsk={onAskPermission} rail={rail} />
          </li>
          <li>
            <FocusItem focus={focus} onToggle={onToggleFocus} rail={rail} />
          </li>
          <li>
            <button
              type="button"
              className={rail ? 'it tipped' : 'it'}
              data-tip={rail ? '사이드바 펴기' : undefined}
              aria-expanded={open}
              aria-controls="side"
              onClick={onToggle}
            >
              <Icon name={rail ? 'sidebar-expand' : 'sidebar-collapse'} />
              <span className="nm">{rail ? '사이드바 펴기' : '사이드바 접기'}</span>
            </button>
          </li>
        </ul>
      </div>
    </aside>
  );
}

function NavItem({ id, count, current, rail }: { id: ScreenId; count: number; current: boolean; rail: boolean }) {
  const s = screenById(id);
  const said = count > 0 && s.countLabel ? s.countLabel(count) : null;
  const tip = said ? `${s.label}, ${said}` : s.label;
  return (
    <a className={rail ? 'it tipped' : 'it'} href={`#${id}`} aria-current={current ? 'page' : undefined} data-tip={rail ? tip : undefined}>
      <Icon name={s.icon} />
      <span className="nm">{s.label}</span>
      {said && (
        <>
          <span className="n num" aria-hidden="true">
            {count}
          </span>
          <span className="sr">, {said}</span>
        </>
      )}
    </a>
  );
}

/**
 * Browser notifications come from the first screen's poll, so the page must stay open (contract
 * 「알림과 집중 모드」 3). Asking is a button; granted or blocked is a status line.
 */
export function NotifyItem({ permission, onAsk, rail, inline = false }: { permission: Permission; onAsk: () => void; rail: boolean; inline?: boolean }) {
  if (permission === 'unsupported') return null;
  if (permission === 'default') {
    return (
      <button type="button" className={rail ? 'it tipped' : 'it'} data-tip={rail ? '브라우저 알림 켜기' : undefined} onClick={onAsk}>
        <Icon name="bell" />
        <span className="nm">브라우저 알림 켜기</span>
      </button>
    );
  }
  const text = permission === 'granted' ? '브라우저 알림 켜짐' : '브라우저 알림이 막혀 있다';
  const fix = permission === 'denied' ? DENIED_FIX : null;
  const tip = fix ? `${text}. ${fix}` : text;
  return (
    <span className={rail || (fix && !inline) ? 'it is-status tipped' : 'it is-status'} data-tip={rail || (fix && !inline) ? tip : undefined}>
      <Icon name="bell" />
      <span className="nm">{text}</span>
      {fix && <span className={inline ? 'why' : 'sr'}>{fix}</span>}
    </span>
  );
}

/**
 * Focus mode (contract 「알림과 집중 모드」 4): while it is on, only a conflict with my live work
 * alerts at once, the rest wait until it ends, and new permission requests and questions go to
 * the terminal instead of here. The button says what pressing it does.
 */
export function FocusItem({ focus, onToggle, rail }: { focus: boolean | null; onToggle: () => void; rail: boolean }) {
  if (focus === null) return null;
  const label = focus ? '집중 모드 끄기' : '집중 모드 켜기';
  const tip = focus ? '집중 모드 끄기: 쌓인 알림을 한 번에 띄우고, 권한 요청을 다시 여기로 중계한다' : '집중 모드 켜기: 충돌만 바로 알리고, 새 권한 요청은 터미널에서 묻는다';
  return (
    <button type="button" className={rail ? 'it tipped' : 'it'} data-tip={rail ? tip : undefined} aria-pressed={focus} onClick={onToggle}>
      <Icon name="focus" />
      <span className="nm">{label}</span>
    </button>
  );
}

import { useRef, useState } from 'react';
import { Icon } from '../icons/Icon';
import { FOOT_ITEMS, NAV_GROUPS, screenById, TAB_ITEMS, type ScreenId } from './screens';
import { Sheet } from './Sheet';
import { FocusItem, NotifyItem, type Counts, type Permission } from './Sidebar';

type Props = {
  current: ScreenId;
  counts: Counts;
  permission: Permission;
  onAskPermission: () => void;
  focus?: boolean | null;
  onToggleFocus?: () => void;
};

/**
 * The bottom tab bar that stands in for the sidebar on narrow screens (DESIGN.md 「화면 배치」 6):
 * four screens and 「더 보기」, which opens a sheet with the rest. CSS shows it only there.
 */
export function TabBar({ current, counts, permission, onAskPermission, focus = null, onToggleFocus = () => {} }: Props) {
  const [more, setMore] = useState(false);
  const moreButton = useRef<HTMLButtonElement>(null);
  const rest = [...NAV_GROUPS.flatMap((g) => g.items), ...FOOT_ITEMS].filter((id) => !TAB_ITEMS.includes(id));
  const inMore = !TAB_ITEMS.includes(current);
  const closeMore = () => {
    setMore(false);
    moreButton.current?.focus();
  };

  return (
    <>
      <nav className="tabbar" aria-label="화면">
        {TAB_ITEMS.map((id) => {
          const s = screenById(id);
          const count = counts[id] ?? 0;
          const said = count > 0 && s.countLabel ? s.countLabel(count) : null;
          return (
            <a key={id} href={`#${id}`} aria-current={id === current ? 'page' : undefined}>
              <Icon name={s.icon} />
              <span>{s.short}</span>
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
        })}
        <button ref={moreButton} type="button" className={inMore ? 'is-here' : undefined} aria-haspopup="dialog" aria-expanded={more} onClick={() => setMore(true)}>
          <Icon name="more" />
          <span>더 보기</span>
        </button>
      </nav>
      {more && (
        <Sheet title="더 보기" onClose={closeMore}>
          <ul className="more-list">
            {rest.map((id) => {
              const s = screenById(id);
              return (
                <li key={id}>
                  <a className="it" href={`#${id}`} aria-current={id === current ? 'page' : undefined} onClick={() => setMore(false)}>
                    <Icon name={s.icon} />
                    <span className="nm">{s.label}</span>
                  </a>
                </li>
              );
            })}
            <li>
              <NotifyItem permission={permission} onAsk={onAskPermission} rail={false} inline />
            </li>
            <li>
              <FocusItem focus={focus} onToggle={onToggleFocus} rail={false} />
            </li>
          </ul>
        </Sheet>
      )}
    </>
  );
}

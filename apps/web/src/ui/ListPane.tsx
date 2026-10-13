import { useId, type ReactNode } from 'react';
import { Icon } from '../icons/Icon';
import { rowKeys } from './rowKeys';

/*
 * The list half of a list-and-detail screen (DESIGN.md 「화면 배치」 3): a head with the count and
 * how the list is grouped, the rows, and the keys at the bottom. The list stays in view under the
 * header while the page scrolls the detail (ui/panes.css). The screen draws the groups and rows.
 */

export type ListHead = {
  /** What the list holds: 「열린 작업」, 「결정」. */
  base: string;
  /** How many rows show; null before the first read. */
  count: number | null;
  /** True when something narrows the list, so the count reads 「열린 작업 중 3건」. */
  narrowed: boolean;
};

type Props = {
  /** The list's name for screen readers: 「작업 목록」. */
  title: string;
  head: ListHead;
  /** How the rows are grouped, after the count: 「상태로 묶음」. */
  by: string;
  /** One button at the right end of the head, like 「새 작업」. */
  action?: ReactNode;
  /** The rows as drawn, for the arrow keys. */
  order: string[];
  /** The arrow keys moved the selection. */
  onSelect: (id: string) => void;
  /** Enter on a row: the detail takes the focus (wide) or opens as a sheet (narrow). */
  onOpen: (id: string) => void;
  children: ReactNode;
};

export function ListPane({ title, head, by, action, order, onSelect, onOpen, children }: Props) {
  const titleId = useId();
  return (
    <section className="pane-list" aria-labelledby={titleId}>
      <div className="pane-head">
        <h2 id={titleId} className="sr">
          {title}
        </h2>
        <p className="pane-count">
          {head.base}
          {head.count !== null && (
            <>
              {head.narrowed ? ' 중 ' : ' '}
              <strong className="num">{head.count}</strong>건
            </>
          )}
          <span className="pane-by">{by}</span>
        </p>
        {action}
      </div>
      <div className="pane-body" onKeyDown={rowKeys(order, onSelect, onOpen)}>
        {children}
      </div>
      <ListKeys />
    </section>
  );
}

/** The keys a list answers to, at its foot. Hidden on narrow screens, where there is no keyboard. */
export function ListKeys() {
  return (
    <p className="pane-keys" aria-hidden="true">
      <span>
        <kbd>
          <Icon name="arrow-up" />
        </kbd>
        <kbd>
          <Icon name="arrow-down" />
        </kbd>
        옮기기
      </span>
      <span>
        <kbd>Enter</kbd> 상세로
      </span>
      <span>
        <kbd>Esc</kbd> 목록으로
      </span>
    </p>
  );
}

import type { KeyboardEvent, ReactNode } from 'react';
import { Sheet } from '../shell/Sheet';

type Props = {
  narrow: boolean;
  /** Narrow only: whether the sheet is open. */
  open: boolean;
  /** The detail's name for screen readers: 「알림 상세」. */
  label: string;
  /** The sheet bar's title on narrow screens. */
  sheetTitle: string;
  onClose: () => void;
  /** Esc in the wide detail: the focus goes back to the selected row. */
  onBack: () => void;
  children: ReactNode;
};

/**
 * The detail half of a list-and-detail screen (DESIGN.md 「화면 배치」 3 and 6): beside the list on
 * wide screens, a sheet over it on narrow ones. Esc in the wide detail returns to the list; inside a
 * field it stays with the field (it clears or closes what the field opened).
 */
export function DetailPane({ narrow, open, label, sheetTitle, onClose, onBack, children }: Props) {
  if (narrow) {
    return open ? (
      <Sheet title={sheetTitle} onClose={onClose}>
        {children}
      </Sheet>
    ) : null;
  }
  const onKeyDown = (e: KeyboardEvent<HTMLElement>) => {
    if (e.key !== 'Escape' || e.defaultPrevented || (e.target as HTMLElement).closest('input, textarea, select')) return;
    e.preventDefault();
    onBack();
  };
  return (
    <section className="pane-detail" aria-label={label} onKeyDown={onKeyDown}>
      {children}
    </section>
  );
}

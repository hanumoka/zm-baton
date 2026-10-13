import type { ReactNode } from 'react';
import { Icon } from '../icons/Icon';

/*
 * Filter chips for the row under the command line (DESIGN.md 「화면 배치」 2): a name with a
 * value the browser's own list picks, or a check that narrows the list. Styles are in ui.css.
 */

/** 「프로젝트 전체 ▾」: the name, then the native select (so the keyboard and screen readers work). */
export function SelectChip({
  label,
  value,
  onChange,
  children,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  /** The `<option>`s. */
  children: ReactNode;
}) {
  return (
    <label className="chip">
      {label}
      <select value={value} onChange={(e) => onChange(e.target.value)} aria-label={label}>
        {children}
      </select>
      <Icon name="chevron-down" className="chev" />
    </label>
  );
}

/** 「☐ 막힘만 2」: a check and what checking it shows. */
export function CheckChip({ checked, onChange, children }: { checked: boolean; onChange: (checked: boolean) => void; children: ReactNode }) {
  return (
    <label className="chip">
      <input type="checkbox" checked={checked} onChange={(e) => onChange(e.target.checked)} />
      {children}
    </label>
  );
}

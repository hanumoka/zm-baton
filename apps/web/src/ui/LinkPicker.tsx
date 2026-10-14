import { useState, type ReactNode } from 'react';
import { Icon } from '../icons/Icon';

export type Pickable = {
  id: string;
  /** The words in the drop-down list. */
  label: string;
  /** How a linked one is drawn in the list above it. */
  node: ReactNode;
};

type Props = {
  legend: string;
  /** What an empty list says. */
  empty: string;
  /** The select's name, 「이을 작업」. */
  pickLabel: string;
  /** The button's name, 「작업 잇기」. */
  addLabel: string;
  /** Everything that can be linked; null while it is read. */
  items: Pickable[] | null;
  ids: string[];
  onChange: (ids: string[]) => void;
};

/**
 * Records linked to the one being written: those linked, each with a button to unlink it, and the
 * browser's own list of the rest with a button to link one more (no combobox to build by hand).
 */
export function LinkPicker({ legend, empty, pickLabel, addLabel, items, ids, onChange }: Props) {
  const [pick, setPick] = useState('');
  const byId = new Map((items ?? []).map((i) => [i.id, i]));
  const rest = (items ?? []).filter((i) => !ids.includes(i.id));
  return (
    <fieldset className="dform-set">
      <legend>{legend}</legend>
      {ids.length === 0 ? (
        <p className="dform-none">{empty}</p>
      ) : (
        <ul className="dc-links">
          {ids.map((id) => (
            <li key={id} className="dform-work">
              {byId.get(id)?.node ?? <code>{id}</code>}
              <button type="button" className="btn btn-quiet" onClick={() => onChange(ids.filter((x) => x !== id))}>
                빼기
              </button>
            </li>
          ))}
        </ul>
      )}
      <div className="wd-form">
        <label>
          {pickLabel}
          <span className="select">
            <select value={pick} onChange={(e) => setPick(e.target.value)} disabled={items === null} aria-busy={items === null || undefined}>
              <option value="">{items === null ? '읽는 중' : rest.length === 0 ? '더 이을 것이 없다' : '고르기'}</option>
              {rest.map((i) => (
                <option key={i.id} value={i.id}>
                  {i.label}
                </option>
              ))}
            </select>
            <Icon name="chevron-down" className="chev" />
          </span>
        </label>
        <button
          type="button"
          className="btn"
          disabled={pick === ''}
          onClick={() => {
            onChange([...ids, pick]);
            setPick('');
          }}
        >
          {addLabel}
        </button>
      </div>
    </fieldset>
  );
}

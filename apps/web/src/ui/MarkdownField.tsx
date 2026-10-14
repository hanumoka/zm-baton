import { useId, useState } from 'react';
import { Prose } from './Prose';

type Props = {
  label: string;
  value: string;
  onChange: (value: string) => void;
  rows?: number;
  placeholder?: string;
  /** What shows in the preview when there is nothing to draw. */
  emptyPreview?: string;
  autoFocus?: boolean;
};

/**
 * A Markdown source field with a preview (G46.5: product documents are edited as Markdown
 * source). Two buttons switch between writing and seeing it drawn by Prose, the same way the
 * detail draws it; the text is kept as written, so nothing is lost between the two.
 */
export function MarkdownField({ label, value, onChange, rows = 14, placeholder, emptyPreview = '미리 볼 내용이 없다.', autoFocus }: Props) {
  const [mode, setMode] = useState<'write' | 'preview'>('write');
  const id = useId();
  return (
    <div className="mdf">
      <div className="mdf-head">
        <label htmlFor={id} className="mdf-label">
          {label}
        </label>
        <div className="mdf-modes" role="group" aria-label={`${label} 보기`}>
          <button type="button" className={mode === 'write' ? 'is-on' : undefined} aria-pressed={mode === 'write'} onClick={() => setMode('write')}>
            쓰기
          </button>
          <button type="button" className={mode === 'preview' ? 'is-on' : undefined} aria-pressed={mode === 'preview'} onClick={() => setMode('preview')}>
            미리보기
          </button>
        </div>
      </div>
      {mode === 'write' ? (
        <textarea id={id} className="field mdf-text" value={value} onChange={(e) => onChange(e.target.value)} rows={rows} placeholder={placeholder} autoFocus={autoFocus} spellCheck={false} />
      ) : (
        <div className="mdf-preview" aria-live="polite">
          {value.trim() === '' ? <p className="dt-none">{emptyPreview}</p> : <Prose text={value} />}
        </div>
      )}
    </div>
  );
}

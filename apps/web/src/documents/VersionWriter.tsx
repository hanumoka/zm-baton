import { useId, useState } from 'react';
import type { DocDetail } from '../documentsApi';
import { Icon } from '../icons/Icon';
import { MarkdownField } from '../ui/MarkdownField';

type Props = {
  /** The document with its newest version shown, whose body the new one starts from. */
  base: DocDetail;
  onSave: (body: string, note: string) => Promise<void>;
  onCancel: () => void;
  error: string | null;
  inSheet?: boolean;
};

/**
 * The next version of a product document (FN-0602): its Markdown body, starting from the newest
 * version, with a preview and a note on what changed. It is saved as a draft; the approver
 * approves it in the detail.
 */
export function VersionWriter({ base, onSave, onCancel, error, inSheet = false }: Props) {
  const latest = base.versions[0] ?? null;
  const start = base.shown && latest && base.shown.number === latest.number ? (base.shown.body_markdown ?? '') : '';
  const [body, setBody] = useState(start);
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const titleId = useId();
  const whyId = useId();
  const next = (latest?.number ?? 0) + 1;
  const same = latest !== null && body === start;
  return (
    <form
      className="wn dform"
      aria-labelledby={titleId}
      aria-busy={busy || undefined}
      onSubmit={(e) => {
        e.preventDefault();
        if (same || busy) return;
        setBusy(true);
        void onSave(body, note.trim()).finally(() => setBusy(false));
      }}
    >
      <h2 id={titleId} className={inSheet ? 'sr' : 'dt-title'}>
        새 판 쓰기
      </h2>
      <p className="wn-lead">
        「{base.document.title}」의 새 판(판 {next})을 쓴다. {latest ? `판 ${latest.number}의 본문에서 시작한다. ` : ''}저장하면 초안이고, 승인자가 승인한다.
      </p>
      {error && (
        <p role="alert" className="wd-refused">
          <Icon name="alert" />
          <span>{error}</span>
        </p>
      )}
      <MarkdownField label="본문" value={body} onChange={setBody} rows={18} autoFocus />
      <label>
        판 메모
        <input className="field" value={note} onChange={(e) => setNote(e.target.value)} placeholder="무엇을 바꿨나" />
      </label>
      <div className="wd-acts">
        <button type="submit" className="btn btn-primary" disabled={same} aria-busy={busy || undefined} aria-describedby={same ? whyId : undefined}>
          판 {next} 저장
        </button>
        <button type="button" className="btn" onClick={onCancel}>
          그만두기
        </button>
      </div>
      {same && (
        <span id={whyId} className="ctl-why">
          본문을 바꾸면 저장할 수 있다.
        </span>
      )}
    </form>
  );
}

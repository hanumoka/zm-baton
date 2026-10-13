import type { MouseEvent, ReactNode } from 'react';
import Markdown, { type Components } from 'react-markdown';
import remarkGfm from 'remark-gfm';

/** What a Markdown link in a zm-docs record points at, decided from its address alone. */
export type LinkTarget = { kind: 'decision'; ref: string } | { kind: 'web'; href: string } | { kind: 'path'; path: string };

const DECISION_FILE = /(?:^|\/)(DEC-\d{4})(?:-[^/#?]*)?\.md(?:[#?].*)?$/;
const WEB = /^https?:\/\//i;

export function linkTarget(href: string | undefined): LinkTarget {
  const h = href ?? '';
  const dec = DECISION_FILE.exec(h);
  if (dec) return { kind: 'decision', ref: dec[1] };
  if (WEB.test(h)) return { kind: 'web', href: h };
  return { kind: 'path', path: h };
}

type Props = {
  /** Markdown from a record's file, as the server hands it over. */
  text: string;
  /** A decision number (DEC-0048) to its id on this screen; null when this screen has not read it. */
  resolveDecision?: (ref: string) => string | null;
  /** Open another decision here (a step in the browser's history, like a supersession link). */
  onJump?: (id: string) => void;
};

/**
 * Markdown from zm-docs records, drawn as text people read rather than its marks. No raw HTML is
 * drawn (react-markdown shows it as text) and images show their alt text, so a record cannot load
 * anything. Links to other decision files open that decision here; web links open in a new tab; other
 * files of the zm-docs checkout have no address in this product, so they stay plain text with the
 * path on hover.
 */
export function Prose({ text, resolveDecision, onJump }: Props) {
  const components: Components = {
    a: ({ href, children }) => <ProseLink href={href} resolveDecision={resolveDecision} onJump={onJump}>{children}</ProseLink>,
    // Sections are h3 in the detail; any heading inside one sits under it.
    h1: ({ children }) => <h4>{children}</h4>,
    h2: ({ children }) => <h4>{children}</h4>,
    h3: ({ children }) => <h4>{children}</h4>,
    h4: ({ children }) => <h4>{children}</h4>,
    h5: ({ children }) => <h4>{children}</h4>,
    h6: ({ children }) => <h4>{children}</h4>,
    img: ({ alt }) => <span className="prose-alt">{alt ?? ''}</span>,
  };
  return (
    <div className="prose">
      <Markdown remarkPlugins={[remarkGfm]} components={components}>
        {text}
      </Markdown>
    </div>
  );
}

function ProseLink({
  href,
  resolveDecision,
  onJump,
  children,
}: {
  href: string | undefined;
  resolveDecision?: (ref: string) => string | null;
  onJump?: (id: string) => void;
  children: ReactNode;
}) {
  const target = linkTarget(href);
  if (target.kind === 'web') {
    return (
      <a href={target.href} target="_blank" rel="noreferrer">
        {children}
      </a>
    );
  }
  if (target.kind === 'decision') {
    const id = resolveDecision?.(target.ref) ?? null;
    if (id === null || !onJump) return <span title={target.ref}>{children}</span>;
    const open = (e: MouseEvent<HTMLAnchorElement>) => {
      if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
      e.preventDefault();
      onJump(id);
    };
    return (
      <a href={`#decisions?item=${encodeURIComponent(id)}`} onClick={open}>
        {children}
      </a>
    );
  }
  return <span title={target.path}>{children}</span>;
}

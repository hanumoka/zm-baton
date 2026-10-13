import type { ReactNode } from 'react';
import { Icon } from '../icons/Icon';

/**
 * The three empty states (DESIGN.md 「상태와 문구」):
 * - `unused`: nothing made yet. Say what it is and offer the one action that makes one.
 * - `empty`: nothing here right now. Say why.
 * - `error`: it could not be read. Say what went wrong and how to fix it, without apologising.
 */
export type EmptyKind = 'unused' | 'empty' | 'error';

type Props = {
  kind: EmptyKind;
  title: string;
  children?: ReactNode;
  /** One button or link. */
  action?: ReactNode;
};

export function EmptyState({ kind, title, children, action }: Props) {
  return (
    <div className={`empty-state is-${kind}`}>
      {kind === 'error' && <Icon name="alert" className="es-mark" />}
      <p className="es-t">{title}</p>
      {children && <p className="es-body">{children}</p>}
      {action && <div className="es-act">{action}</div>}
    </div>
  );
}

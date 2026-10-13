import { Fragment } from 'react';

/**
 * A repository path in monospace that wraps after a `/` before it wraps inside a name, so a long
 * path in a narrow column still reads folder by folder.
 */
export function Path({ path, className }: { path: string; className?: string }) {
  const parts = path.split('/');
  return (
    <code className={className}>
      {parts.map((p, i) => (
        <Fragment key={i}>
          {p}
          {i < parts.length - 1 && (
            <>
              /<wbr />
            </>
          )}
        </Fragment>
      ))}
    </code>
  );
}

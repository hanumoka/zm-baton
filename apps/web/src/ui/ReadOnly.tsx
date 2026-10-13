import { Icon } from '../icons/Icon';

export type GitRef = { repo_key?: string; path?: string; commit?: string } | null;

/**
 * 「읽기만 한다」 in a detail's head, with where the source of truth is: the Git file and the commit
 * it was read at, or else the server's reason. Items copied from a Git repository change there and
 * come back with the next import.
 */
export function ReadOnly({ gitRef, reason }: { gitRef: GitRef; reason: string | null }) {
  const path = gitRef?.path;
  const commit = gitRef?.commit;
  return (
    <p className="dt-ro">
      <Icon name="lock" />
      <strong>읽기만 한다</strong>
      {path ? (
        <span>
          정본은 Git 파일 <code>{path}</code>
          {commit && (
            <>
              {' '}
              읽은 커밋 <code>{commit.slice(0, 10)}</code>
            </>
          )}
        </span>
      ) : (
        <span>{reason}</span>
      )}
    </p>
  );
}

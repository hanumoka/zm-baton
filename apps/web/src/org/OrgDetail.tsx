import { useId, type ReactNode, type Ref } from 'react';
import { Icon } from '../icons/Icon';
import type { OrgPerson, OrgView } from '../orgApi';
import { visibilityLabel, VISIBILITY_NOTES } from '../ui/visibility';
import { ArchiveControl, KindControl, LinkControl, MembersControl, NameControl, Refused, RemoteControl, RolesControl, StatusControl, type Refusal, type Run } from './OrgControls';
import { envLabel, GIT_NAME_REASON, KIND_LABELS, kindOf, linkOptions, linksOf, nameOf, roleLabel, ROLE_NOTES, ROLE_ORDER, rolesLine, sortRoles, type EntryKind } from './orgView';

type Props = {
  org: OrgView;
  id: string;
  run: Run;
  refusal: Refusal;
  titleRef?: Ref<HTMLHeadingElement>;
  inSheet?: boolean;
  onOpen: (id: string) => void;
};

/**
 * One item of the organisation (FN-0101): what it is, who and what it is tied to, and, for a person
 * who may change the structure, the controls. The organisation's own row explains the roles and the
 * sharing scopes, so a one-person organisation reads what the structure will mean once it grows.
 */
export function OrgDetail({ org, id, run, refusal, titleRef, inSheet = false, onOpen }: Props) {
  const titleId = useId();
  const kind = kindOf(org, id);
  if (kind === null) return null;
  const name = kind === 'org' ? org.name : nameOf(org, id);
  // The key resets the controls' drafts when the server's answer changes the item.
  const stamp = JSON.stringify([org.people.find((p) => p.id === id), org.links.filter((l) => l.from_id === id || l.to_id === id)]);
  return (
    <article className="dt" aria-labelledby={titleId} key={`${id}:${stamp}`}>
      {!inSheet && (
        <nav className="dt-crumb" aria-label="위치">
          <span>조직</span>
          <span aria-hidden="true">/</span>
          <span>{KIND_LABELS[kind]}</span>
        </nav>
      )}
      <h2 id={titleId} ref={titleRef} className="dt-title" tabIndex={-1}>
        {name}
      </h2>
      <Facts org={org} id={id} kind={kind} />
      <div className="dt-grid is-solo">
        <div className="dt-main">
          <Body org={org} id={id} kind={kind} run={run} refusal={refusal} onOpen={onOpen} />
        </div>
      </div>
    </article>
  );
}

function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <dt>{label}</dt>
      <dd>{children}</dd>
    </div>
  );
}

function Facts({ org, id, kind }: { org: OrgView; id: string; kind: EntryKind }) {
  const owner = org.people.find((p) => p.id === org.owner_user_id);
  const team = org.teams.find((t) => t.id === id);
  const product = org.products.find((p) => p.id === id);
  const project = org.projects.find((p) => p.id === id);
  const repo = org.repositories.find((r) => r.id === id);
  const env = org.environments.find((e) => e.id === id);
  const person = org.people.find((p) => p.id === id);
  return (
    <dl className="dt-facts">
      {kind === 'org' && (
        <>
          <Fact label="형태">{org.solo ? '1인 조직' : '팀 조직'}</Fact>
          <Fact label="소유자">{owner ? owner.display_name : <code>{org.owner_user_id}</code>}</Fact>
          <Fact label="사람">{`${org.people.filter((p) => p.status === 'active').length}명`}</Fact>
        </>
      )}
      {person && (
        <>
          <Fact label="상태">{person.status === 'active' ? '활동 중' : '비활성'}</Fact>
          <Fact label="역할">{rolesLine(person)}</Fact>
          <Fact label="매니저">{person.manager_id ? <code>{person.manager_id}</code> : '없음'}</Fact>
          <Fact label="번호">
            <code>{person.id}</code>
          </Fact>
        </>
      )}
      {team && <Fact label="사람">{`${team.members.length}명`}</Fact>}
      {(team ?? product ?? repo ?? env) && <Fact label="보관">{(team ?? product ?? repo ?? env)?.archived_at ? '보관됨' : '쓰는 중'}</Fact>}
      {project && (
        <>
          {project.external_ref && (
            <Fact label="정본">
              Git <code>{project.external_ref}</code>
            </Fact>
          )}
          <Fact label="종류">{project.kind ?? '적혀 있지 않다'}</Fact>
          <Fact label="사람">{`${project.members.length}명`}</Fact>
        </>
      )}
      {repo && (
        <>
          <Fact label="저장소 키">
            <code>{repo.repo_key}</code>
          </Fact>
          <Fact label="원격">{repo.remote ? <code>{repo.remote}</code> : '적혀 있지 않다'}</Fact>
          <Fact label="PC">{`${repo.devices}대`}</Fact>
        </>
      )}
      {env && <Fact label="종류">{envLabel(env.kind)}</Fact>}
    </dl>
  );
}

function Body({ org, id, kind, run, refusal, onOpen }: { org: OrgView; id: string; kind: EntryKind; run: Run; refusal: Refusal; onOpen: (id: string) => void }) {
  const can = org.can_manage;
  const person = org.people.find((p) => p.id === id);
  const team = org.teams.find((t) => t.id === id);
  const project = org.projects.find((p) => p.id === id);
  const repo = org.repositories.find((r) => r.id === id);
  const env = org.environments.find((e) => e.id === id);
  const archivable = kind === 'team' || kind === 'product' || kind === 'repository' || kind === 'environment';
  const archived = archivable && Boolean((org.teams.find((t) => t.id === id) ?? org.products.find((p) => p.id === id) ?? repo ?? env)?.archived_at);
  const name = kind === 'org' ? org.name : nameOf(org, id);
  return (
    <>
      {!can && (
        <p className="org-note org-readonly">
          <Icon name="lock" />
          <span>조직 구조는 운영자 역할만 바꾼다. 여기서는 읽기만 한다.</span>
        </p>
      )}
      {can && (
        <section className="dt-sec">
          <h3>이름</h3>
          <NameControl name={name} label={`${KIND_LABELS[kind]} 이름`} locked={project?.external_ref ? GIT_NAME_REASON : null} run={run} refusal={refusal} />
        </section>
      )}
      {person && <PersonSections org={org} person={person} can={can} run={run} refusal={refusal} onOpen={onOpen} />}
      {(team || project) && (
        <section className="dt-sec">
          <h3>사람</h3>
          <Names org={org} ids={(team ?? project)!.members} empty="아직 사람이 없다" onOpen={onOpen} />
          {can && <MembersControl org={org} members={(team ?? project)!.members} run={run} refusal={refusal} />}
        </section>
      )}
      {kind !== 'org' && kind !== 'person' && <LinkSection org={org} id={id} kind={kind} can={can} run={run} refusal={refusal} onOpen={onOpen} />}
      {can && (project || env) && (
        <section className="dt-sec">
          <h3>종류</h3>
          <KindControl value={(project ? project.kind : env!.kind) ?? ''} envKinds={env ? org.environment_kinds : null} run={run} refusal={refusal} />
        </section>
      )}
      {can && repo && (
        <section className="dt-sec">
          <h3>원격 주소</h3>
          <RemoteControl value={repo.remote ?? ''} run={run} refusal={refusal} />
        </section>
      )}
      {can && archivable && (
        <section className="dt-sec">
          <h3>보관</h3>
          <ArchiveControl archived={archived} run={run} refusal={refusal} />
        </section>
      )}
      {kind === 'org' && <OrgSections org={org} />}
    </>
  );
}

function PersonSections({ org, person, can, run, refusal, onOpen }: { org: OrgView; person: OrgPerson; can: boolean; run: Run; refusal: Refusal; onOpen: (id: string) => void }) {
  return (
    <>
      <section className="dt-sec">
        <h3>역할</h3>
        {can ? (
          <RolesControl roles={person.roles} owner={person.owner} canManage={can} run={run} refusal={refusal} />
        ) : (
          <ul className="org-chips">
            {(person.owner ? ROLE_ORDER : sortRoles(person.roles)).map((r) => (
              <li key={r} className="org-chip">
                {roleLabel(r)}
              </li>
            ))}
            {!person.owner && person.roles.length === 0 && <li className="dt-none">역할 없음</li>}
          </ul>
        )}
      </section>
      {can && (
        <section className="dt-sec">
          <h3>상태</h3>
          <StatusControl status={person.status} owner={person.owner} run={run} refusal={refusal} />
          {person.owner && <p className="org-note">조직 소유자는 늘 활동 중이다.</p>}
        </section>
      )}
      <section className="dt-sec">
        <h3>팀과 프로젝트</h3>
        <Names org={org} ids={[...person.teams, ...person.projects]} empty="속한 팀이나 프로젝트가 없다" onOpen={onOpen} />
      </section>
    </>
  );
}

/** The links of an item, each with the other end and, for an operator, a button to undo it. */
function LinkSection({ org, id, kind, can, run, refusal, onOpen }: { org: OrgView; id: string; kind: EntryKind; can: boolean; run: Run; refusal: Refusal; onOpen: (id: string) => void }) {
  const links = linksOf(org, id);
  return (
    <section className="dt-sec">
      <h3>이어진 항목</h3>
      {links.length === 0 ? (
        <p className="dt-none">아직 이어진 항목이 없다.</p>
      ) : (
        <ul className="org-links">
          {links.map(({ link, other, otherKind }) => (
            <li key={`${link.from_id}>${link.to_id}`}>
              <span className="org-kind">{KIND_LABELS[otherKind]}</span>
              <button type="button" className="org-link" onClick={() => onOpen(other)}>
                {nameOf(org, other)}
              </button>
              {can && (
                <button type="button" className="btn btn-quiet" onClick={() => void run({ kind: 'unlink', fromId: link.from_id, toId: link.to_id })}>
                  끊기
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
      <Refused refusal={refusal} where="unlink" />
      {can && <LinkControl options={linkOptions(org, kind, id)} run={run} refusal={refusal} />}
    </section>
  );
}

function Names({ org, ids, empty, onOpen }: { org: OrgView; ids: string[]; empty: string; onOpen: (id: string) => void }) {
  if (ids.length === 0) return <p className="dt-none">{empty}</p>;
  return (
    <ul className="org-links">
      {ids.map((m) => (
        <li key={m}>
          <span className="org-kind">{KIND_LABELS[kindOf(org, m) ?? 'person']}</span>
          <button type="button" className="org-link" onClick={() => onOpen(m)}>
            {nameOf(org, m)}
          </button>
        </li>
      ))}
    </ul>
  );
}

/** What the roles and the sharing scopes mean: the words the rest of the screens use. */
function OrgSections({ org }: { org: OrgView }) {
  return (
    <>
      <section className="dt-sec">
        <h3>역할</h3>
        <dl className="org-legend">
          {org.roles.map((r) => (
            <div key={r.id}>
              <dt>{r.label}</dt>
              <dd>{ROLE_NOTES[r.id] ?? ''}</dd>
            </div>
          ))}
        </dl>
        <p className="org-note">역할이 할 수 있는 일을 정하고, 공유 범위가 볼 수 있는 기록을 정한다. 서버가 요청마다 둘을 함께 본다.</p>
      </section>
      <section className="dt-sec">
        <h3>공유 범위</h3>
        <dl className="org-legend">
          {org.visibilities.map((v) => (
            <div key={v.id}>
              <dt>{visibilityLabel(v.id)}</dt>
              <dd>{VISIBILITY_NOTES[v.id] ?? ''}</dd>
            </div>
          ))}
        </dl>
      </section>
    </>
  );
}

import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { Shell } from './Shell';
import './tokens.css';
import './base.css';
import './ui/ui.css';
import './shell/shell.css';
import './styles.css';
import './ui/panes.css';
import './ui/prose.css';
import './work/work.css';
import './decisions/decisions.css';
import './documents/documents.css';
import './inbox/inbox.css';
import './ui/contradictions.css';
import './sessions/sessions.css';
import './accounts/accounts.css';
import './kit/kit.css';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Shell />
  </StrictMode>,
);

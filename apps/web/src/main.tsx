import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { Shell } from './Shell';
import './tokens.css';
import './base.css';
import './ui/ui.css';
import './shell/shell.css';
import './styles.css';
import './ui/panes.css';
import './work/work.css';
import './kit/kit.css';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Shell />
  </StrictMode>,
);

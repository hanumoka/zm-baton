import { call } from './workApi';

export type ServerAdministrationView = {
  organization_id: string;
  organization_name: string;
  owner_user_id: string;
  administrator_user_id: string;
  administrator_name: string;
  initialized_at: string | null;
  auth_mode: 'local' | 'required';
};

export const serverApi = {
  view: (signal?: AbortSignal) => call<ServerAdministrationView>('GET', '/api/server', undefined, signal),
  initialize: (organizationName: string, administratorName: string) =>
    call<ServerAdministrationView>('POST', '/api/server/initialize', { organization_name: organizationName, administrator_name: administratorName }),
  transfer: (userId: string) => call<ServerAdministrationView>('PUT', '/api/server/administrator', { user_id: userId }),
};

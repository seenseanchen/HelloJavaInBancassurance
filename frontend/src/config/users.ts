export interface DemoUser {
  username: string
  password: string
  displayName: string
  role: 'agent' | 'underwriter'
}

export const DEMO_USERS: readonly DemoUser[] = [
  {
    username: 'demo',
    password: 'demo',
    displayName: 'Demo Agent',
    role: 'agent',
  },
  {
    username: 'underwriter',
    password: '1234',
    displayName: 'Demo Underwriter',
    role: 'underwriter',
  },
  {
    username: 'agent',
    password: '1234',
    displayName: 'Demo Agent (Plan)',
    role: 'agent',
  },
] as const

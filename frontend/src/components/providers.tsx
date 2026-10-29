'use client';
import { createContext, useContext, useState, useSyncExternalStore } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import type { User } from '@/lib/api';

const UserContext = createContext<User>('alice');
export const useUser = () => useContext(UserContext);
function subscribe(callback: () => void) {
  window.addEventListener('storage', callback);
  window.addEventListener('demo-user', callback);
  return () => { window.removeEventListener('storage', callback); window.removeEventListener('demo-user', callback); };
}
function snapshot(): User { return localStorage.getItem('demo-user') === 'bob' ? 'bob' : 'alice'; }
export function Providers({ children }: { children: React.ReactNode }) {
  const [client] = useState(() => new QueryClient({ defaultOptions: { queries: { retry: 1, staleTime: 0 } } }));
  const user = useSyncExternalStore(subscribe, snapshot, () => null);
  const pathname = usePathname();
  function change(value: string) {
    void client.cancelQueries();
    localStorage.setItem('demo-user', value);
    window.dispatchEvent(new Event('demo-user'));
  }
  return <QueryClientProvider client={client}>
    <header className="site-header">
      <Link href="/" className="brand" aria-label="素材工坊首页"><span className="brand-mark">m<span>✦</span></span><span>素材工坊<small>MATERIAL STUDIO</small></span></Link>
      <nav aria-label="主导航"><Link className={pathname === '/' ? 'active' : ''} href="/">发现模板</Link><Link className={pathname === '/history' ? 'active' : ''} href="/history">制作历史</Link></nav>
      <label className="user-switch"><span className="avatar">{user === 'bob' ? 'B' : 'A'}</span><span className="sr-only">演示用户</span><select aria-label="演示用户" value={user ?? 'alice'} onChange={e => change(e.target.value)}><option value="alice">Alice</option><option value="bob">Bob</option></select><span className="demo-tag">演示</span></label>
    </header>
    {user ? <UserContext value={user}><main key={user}>{children}</main></UserContext> : <main><p role="status">正在恢复工作台…</p></main>}
    <footer><span>✦ 每一个灵感，都值得被制作。</span><span>本地模拟制作 · 演示身份，非安全认证</span></footer>
  </QueryClientProvider>;
}

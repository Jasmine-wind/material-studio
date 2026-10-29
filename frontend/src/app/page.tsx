'use client';
import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import Link from 'next/link';
import { api, type Template } from '@/lib/api';
import { useUser } from '@/components/providers';

export default function Home() {
  const user = useUser();
  const [category, setCategory] = useState('全部灵感');
  const query = useQuery({ queryKey: ['templates', user], queryFn: ({ signal }) => api<Template[]>('/api/templates', user, signal) });
  const categories = ['全部灵感', ...new Set(query.data?.map(t => t.category) ?? [])];
  const filtered = query.data?.filter(t => category === '全部灵感' || t.category === category);
  return <>
    <section className="hero"><div><p className="eyebrow">YOUR NEXT CREATION STARTS HERE</p><h1>让灵感，<span>成为作品。</span></h1><p className="muted">从一张照片出发，发现日常的另一种可能。</p></div><div className="hero-note"><span>✧</span>选择模板 / 上传素材 / 静待呈现<small>无需复杂操作，创作即刻开始</small></div></section>
    <section aria-label="模板库"><div className="section-heading"><h2>灵感模板 <span className="count">06</span></h2><span className="muted small">为你的下一次创作，挑选一个起点</span></div>
      <div className="filters" role="group" aria-label="模板分类">{categories.map(c => <button key={c} aria-pressed={category === c} className={category === c ? 'selected' : ''} onClick={() => setCategory(c)}>{c}</button>)}</div>
      {query.isPending && <div className="template-grid" role="status" aria-label="正在加载模板">{Array.from({ length: 6 }, (_, i) => <div key={i} className="skeleton cover-skeleton" />)}</div>}
      {query.isError && <div className="error" role="alert">模板暂时无法加载。<button onClick={() => void query.refetch()}>重新加载</button></div>}
      {filtered?.length === 0 && <div className="empty">这个分类还没有模板。</div>}
      <div className="template-grid">{filtered?.map((t, i) => <Link className="template-card" key={t.id} href={`/templates/${t.id}`}><div className="cover-wrap"><img src={t.coverUrl} alt={t.title + '自制封面'} loading={i < 3 ? 'eager' : 'lazy'} /><span className="image-count">▧ {t.requiredImageCount} 张素材</span><span className="card-cta">开始制作 ↗</span></div><div className="card-info"><div><h3>{t.title}</h3><span>{t.category}</span></div><span className="card-arrow">↗</span></div></Link>)}</div>
    </section>
    <aside className="studio-note"><span>✦</span><div>简单三步，留住你的灵感<p>选择喜欢的模板，上传 PNG / JPEG 图片，制作进度与结果会自动保存在个人历史中。</p></div><Link href="/history">查看我的作品 →</Link></aside>
  </>;
}

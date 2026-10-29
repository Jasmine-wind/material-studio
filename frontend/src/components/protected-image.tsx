'use client';
import { useEffect, useState } from 'react';
import { imageBlob } from '@/lib/api';
import { useUser } from './providers';

export function ProtectedImage({ path, alt }: { path: string; alt: string }) {
  const user = useUser();
  const [src, setSrc] = useState<string>();
  const [error, setError] = useState(false);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    let objectUrl: string | undefined;
    setSrc(undefined); setError(false);
    imageBlob(path, user, controller.signal).then(blob => {
      if (controller.signal.aborted) return;
      objectUrl = URL.createObjectURL(blob); setSrc(objectUrl);
    }).catch(() => { if (!controller.signal.aborted) setError(true); });
    return () => { controller.abort(); if (objectUrl) URL.revokeObjectURL(objectUrl); };
  }, [path, user, attempt]);
  if (error) return <button className="image-error" onClick={() => setAttempt(a => a + 1)}>图片暂时无法加载 · 重试</button>;
  return src ? <img className="result-image" src={src} alt={alt} /> : <div className="image-loading skeleton" role="status">加载图片…</div>;
}

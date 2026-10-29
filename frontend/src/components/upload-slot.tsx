'use client';
import { useEffect, useRef, useState } from 'react';
import { api, type Asset, type Template } from '@/lib/api';
import { useUser } from './providers';

export function UploadSlot({ index, template, disabled, onChange }: { index: number; template: Template; disabled: boolean; onChange: (index: number, asset: Asset | null, busy: boolean) => void }) {
  const user = useUser();
  const [file, setFile] = useState<File>();
  const [preview, setPreview] = useState<string>();
  const [state, setState] = useState<'empty' | 'uploading' | 'ready' | 'error'>('empty');
  const [error, setError] = useState('');
  const controller = useRef<AbortController | null>(null);
  useEffect(() => () => controller.current?.abort(), []);
  useEffect(() => {
    if (!file) { setPreview(undefined); return; }
    const url = URL.createObjectURL(file); setPreview(url);
    return () => URL.revokeObjectURL(url);
  }, [file]);
  async function choose(next?: File) {
    if (!next) return;
    controller.current?.abort();
    setError(''); onChange(index, null, false);
    if (!template.acceptedMimeTypes.includes(next.type) || next.size === 0 || next.size > template.maxFileSizeBytes) {
      setFile(undefined); setState('error'); setError('请选择不超过 10MB 的 PNG / JPEG 图片'); return;
    }
    setFile(next); setState('uploading'); onChange(index, null, true);
    const active = new AbortController(); controller.current = active;
    const form = new FormData(); form.append('file', next);
    try {
      const asset = await api<Asset>('/api/assets', user, active.signal, { method: 'POST', body: form });
      if (!active.signal.aborted) { setState('ready'); onChange(index, asset, false); }
    } catch (e) {
      if (!active.signal.aborted) { setState('error'); setError(e instanceof Error ? e.message : '上传失败'); onChange(index, null, false); }
    }
  }
  function remove() { controller.current?.abort(); setFile(undefined); setError(''); setState('empty'); onChange(index, null, false); }
  const locked = disabled || state === 'uploading';
  return <div className={`upload-slot ${state}`}>
    <label className="upload-target" htmlFor={`upload-${index}`}>
      {preview ? <img src={preview} alt={`素材 ${index + 1} 预览`} /> : <><span className="upload-plus">＋</span><strong>素材 {index + 1}</strong><span>点击选择图片</span></>}
      <input id={`upload-${index}`} aria-label={`选择素材 ${index + 1}`} type="file" accept="image/png,image/jpeg" disabled={locked} onChange={e => { void choose(e.target.files?.[0]); e.target.value = ''; }} />
    </label>
    <div className="upload-bottom"><span role="status">{state === 'uploading' ? '正在上传…' : state === 'ready' ? '✓ 上传完成' : state === 'error' ? '上传未完成' : 'PNG / JPEG'}</span>{file && <div><label className={locked ? 'disabled' : ''} htmlFor={`upload-${index}`}>替换<span className="sr-only">素材 {index+1}</span></label><button type="button" disabled={locked} aria-label={`移除素材 ${index + 1}`} onClick={remove}>移除</button></div>}</div>
    {error && <p className="slot-error" role="alert">{error}</p>}
  </div>;
}

import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TaskList, taskPollingInterval } from './task-list';
import type { Task, User } from '@/lib/api';

let user: User = 'alice';
vi.mock('./providers', () => ({ useUser: () => user }));
vi.mock('./protected-image', () => ({ ProtectedImage: ({ alt }: { alt: string }) => <span>{alt}</span> }));
const base = (id: string, status: Task['status']): Task => ({ id, templateId: 'lunar', templateTitle: '月下漫游', status, stage: 'GENERATE', failedStage: status === 'FAILED' ? 'GENERATE' : null, errorMessage: status === 'FAILED' ? '指定失败' : null, resultUrl: status === 'SUCCEEDED' ? '/result' : null, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z', finishedAt: null });
const response = (data: Task[]) => new Response(JSON.stringify(data), { headers: { 'Content-Type': 'application/json' } });
function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  const view = render(<QueryClientProvider client={client}><TaskList /></QueryClientProvider>);
  return { client, ...view };
}
beforeEach(() => { user = 'alice'; });
afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers(); });
describe('server-owned task state', () => {
  it('updates simultaneous task IDs independently, preserving success and failure', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(response([base('one','RUNNING'),base('two','QUEUED')]))
      .mockResolvedValueOnce(response([base('one','SUCCEEDED'),base('two','FAILED')]));
    vi.stubGlobal('fetch', fetcher);
    const { client } = setup();
    expect(await screen.findByTestId('task-one')).toHaveTextContent('制作中');
    await act(async () => { await client.invalidateQueries({ queryKey: ['tasks','alice'] }); });
    expect(within(screen.getByTestId('task-one')).getByText('已完成')).toBeInTheDocument();
    expect(screen.getByTestId('task-one')).not.toHaveTextContent('指定失败');
    expect(screen.getByTestId('task-two')).toHaveTextContent('指定失败');
  });
  it.each(['http', 'network'])('keeps old business state on %s query errors', async kind => {
    const fetcher=vi.fn().mockResolvedValueOnce(response([base('one','RUNNING')]));
    if (kind==='http') fetcher.mockResolvedValue(new Response('{"message":"offline"}',{status:503}));
    else fetcher.mockRejectedValue(new TypeError('network unavailable'));
    vi.stubGlobal('fetch',fetcher);
    const { client }=setup();
    await screen.findByTestId('task-one');
    await act(async()=>{await client.invalidateQueries({queryKey:['tasks','alice']});});
    expect(await screen.findByRole('alert')).toHaveTextContent('状态暂时无法同步');
    expect(screen.getByTestId('task-one')).toHaveTextContent('制作中');
    expect(screen.getByTestId('task-one')).not.toHaveTextContent('制作失败');
    fireEvent.click(screen.getByRole('button',{name:'重试'}));
    await waitFor(()=>expect(fetcher.mock.calls.length).toBeGreaterThan(2));
  });
  it('polls active tasks and stops actual polling when all tasks are terminal', async () => {
    vi.useFakeTimers();
    const fetcher=vi.fn().mockResolvedValueOnce(response([base('one','RUNNING')]))
      .mockImplementation(()=>Promise.resolve(response([base('one','SUCCEEDED'),base('two','FAILED')])));
    vi.stubGlobal('fetch',fetcher);
    const { client }=setup();
    await act(async()=>{await vi.advanceTimersByTimeAsync(20);});
    expect(fetcher).toHaveBeenCalledTimes(1);
    await act(async()=>{await vi.advanceTimersByTimeAsync(2000);});
    expect(fetcher).toHaveBeenCalledTimes(2);
    await act(async()=>{await vi.advanceTimersByTimeAsync(10000);});
    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(taskPollingInterval([base('q','QUEUED')])).toBe(2000);
    expect(taskPollingInterval([])).toBe(false);
    client.clear();
  });
  it('aborts the old user request and prevents its late result from overwriting Bob', async () => {
    let resolveAlice!: (r: Response)=>void;
    let oldSignal: AbortSignal | undefined;
    const fetcher=vi.fn((_path: string, init: RequestInit)=>{
      const owner=new Headers(init.headers).get('X-Demo-User');
      if(owner==='alice'){oldSignal=init.signal as AbortSignal;return new Promise<Response>(r=>{resolveAlice=r;});}
      return Promise.resolve(response([base('bob-only','SUCCEEDED')]));
    });
    vi.stubGlobal('fetch',fetcher);
    const { client, rerender }=setup();
    await waitFor(()=>expect(fetcher).toHaveBeenCalledTimes(1));
    user='bob';
    rerender(<QueryClientProvider client={client}><TaskList /></QueryClientProvider>);
    expect(await screen.findByTestId('task-bob-only')).toBeInTheDocument();
    expect(oldSignal?.aborted).toBe(true);
    await act(async()=>{resolveAlice(response([base('alice-late','FAILED')]));});
    expect(screen.queryByTestId('task-alice-late')).not.toBeInTheDocument();
    expect(screen.getByTestId('task-bob-only')).toHaveTextContent('已完成');
  });
});

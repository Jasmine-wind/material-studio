"use client";
import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { api, isActive, stageNames, type Task } from "@/lib/api";
import { useUser } from "./providers";
import { ProtectedImage } from "./protected-image";

export const taskPollingInterval = (tasks: Task[] | undefined) =>
  tasks?.some(isActive) ? 2000 : false;
const statusNames = {
  QUEUED: "等待制作",
  RUNNING: "制作中",
  SUCCEEDED: "已完成",
  FAILED: "制作失败",
};
export function TaskList({ compact = false }: { compact?: boolean }) {
  const user = useUser();
  const query = useQuery({
    queryKey: ["tasks", user],
    queryFn: ({ signal }) => api<Task[]>("/api/tasks", user, signal),
    refetchInterval: (q) => taskPollingInterval(q.state.data),
    refetchOnMount: "always",
    refetchOnWindowFocus: true,
  });
  const tasks = compact ? query.data?.slice(0, 6) : query.data;
  return (
    <section
      className={compact ? "task-panel" : "history-panel"}
      aria-label="个人任务"
    >
      <div className="section-heading">
        <h2>
          {compact ? "最近制作" : "我的制作"}{" "}
          <span className="count">{query.data?.length ?? "—"}</span>
        </h2>
        <button
          className="text-button"
          disabled={query.isFetching}
          onClick={() => void query.refetch()}
        >
          {query.isFetching ? "同步中…" : "↻ 手动刷新"}
        </button>
      </div>
      {query.isError && (
        <div className="error" role="alert">
          状态暂时无法同步，已保留上次状态。
          <button onClick={() => void query.refetch()}>重试</button>
        </div>
      )}
      {query.isPending && (
        <div className="empty" role="status">
          正在读取制作记录…
        </div>
      )}
      {tasks?.length === 0 && (
        <div className="empty">
          <span className="empty-icon">▧</span>
          <h3>还没有作品</h3>
          <p>
            从一个喜欢的模板开始，
            <br />
            你的创作会保存在这里。
          </p>
          <Link href="/">发现灵感 →</Link>
        </div>
      )}
      <div className={compact ? "task-stack" : "history-grid"}>
        {tasks?.map((task) => (
          <article
            className="task-card"
            key={task.id}
            data-testid={`task-${task.id}`}
          >
            <div className="task-top">
              <Link href={`/templates/${task.templateId}`}>
                {task.templateTitle}
              </Link>
              <span className={`status ${task.status.toLowerCase()}`}>
                {isActive(task) && <i />}
                {statusNames[task.status]}
              </span>
            </div>
            <div className="task-meta">
              <time dateTime={task.createdAt}>
                {new Date(task.createdAt).toLocaleString("zh-CN", {
                  hour12: false,
                })}
              </time>
              <span title={task.id}>#{task.id.slice(0, 8)}</span>
            </div>
            {isActive(task) && (
              <>
                <div className="stage-track">
                  {(["CHECK_MATERIAL", "GENERATE", "FINALIZE"] as const).map(
                    (stage, index) => (
                      <div
                        key={stage}
                        className={
                          task.stage === stage
                            ? "current"
                            : index <
                                [
                                  "CHECK_MATERIAL",
                                  "GENERATE",
                                  "FINALIZE",
                                ].indexOf(task.stage)
                              ? "done"
                              : ""
                        }
                      >
                        <span />
                        {stageNames[stage]}
                      </div>
                    ),
                  )}
                </div>
                <p className="task-hint" role="status">
                  {task.status === "QUEUED"
                    ? "已受理，正在等待可用制作位置"
                    : `正在${stageNames[task.stage]}，可以离开页面`}
                </p>
              </>
            )}
            {task.status === "FAILED" && (
              <div className="business-error">
                <strong>{stageNames[task.failedStage ?? "NONE"]}失败</strong>
                <p>{task.errorMessage}</p>
              </div>
            )}
            {task.status === "SUCCEEDED" && task.resultUrl && (
              <div className="result-wrap">
                <ProtectedImage
                  path={task.resultUrl}
                  alt={`${task.templateTitle}制作结果`}
                />
                <p>制作完成 · 本地模拟预览</p>
              </div>
            )}
          </article>
        ))}
      </div>
      {compact && (
        <Link className="all-history" href="/history">
          查看全部制作记录 ↗
        </Link>
      )}
      <p className="small muted panel-note">
        进度自动同步 · 刷新页面不会中断后台制作
      </p>
    </section>
  );
}

"use client";
import { use } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { api, ApiError, type Template } from "@/lib/api";
import { useUser } from "@/components/providers";
import { Maker } from "@/components/maker";
import { TaskList } from "@/components/task-list";

export default function Detail({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = use(params);
  const user = useUser();
  const query = useQuery({
    queryKey: ["template", user, id],
    queryFn: ({ signal }) =>
      api<Template>(`/api/templates/${encodeURIComponent(id)}`, user, signal),
    retry: (count, error) =>
      !(error instanceof ApiError && error.status === 404) && count < 1,
  });
  if (query.isPending)
    return (
      <div className="empty" role="status">
        正在准备你的创作空间…
      </div>
    );
  if (query.isError)
    return (
      <div className="empty">
        <h1>
          {query.error instanceof ApiError && query.error.status === 404
            ? "模板不存在"
            : "模板暂时无法加载"}
        </h1>
        <button onClick={() => void query.refetch()}>重试</button>
        <p>
          <Link href="/">← 返回模板库</Link>
        </p>
      </div>
    );
  const t = query.data;
  return (
    <>
      <Link className="back-link" href="/">
        ← 返回模板库
      </Link>
      <div className="detail-layout">
        <div className="creation-column">
          <section className="template-intro">
            <img
              className="detail-cover"
              src={t.coverUrl}
              alt={t.title + "模板封面"}
            />
            <div>
              <p className="eyebrow">{t.category} / TEMPLATE</p>
              <h1>{t.title}</h1>
              <p className="muted">{t.description}</p>
              <div className="template-facts">
                <span>▧ {t.requiredImageCount} 张素材</span>
                <span>三步自动制作</span>
              </div>
              <p className="intro-caption">
                一点素材，
                <br />
                一种全新的表达。
              </p>
              <a className="sample-link" href="/demo/sample-1.png" download>
                ↓ 下载示例图片
              </a>
            </div>
          </section>
          <Maker key={id} template={t} />
        </div>
        <TaskList compact />
      </div>
    </>
  );
}

export type User = "alice" | "bob";
export type Stage = "NONE" | "CHECK_MATERIAL" | "GENERATE" | "FINALIZE";
export type Template = {
  id: string;
  title: string;
  category: string;
  coverUrl: string;
  description: string;
  requiredImageCount: number;
  acceptedMimeTypes: string[];
  maxFileSizeBytes: number;
};
export type Task = {
  id: string;
  templateId: string;
  templateTitle: string;
  status: "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED";
  stage: Stage;
  failedStage: Stage | null;
  errorMessage: string | null;
  resultUrl: string | null;
  createdAt: string;
  updatedAt: string;
  finishedAt: string | null;
};
export type Asset = {
  id: string;
  mimeType: string;
  sizeBytes: number;
  previewUrl: string;
};
export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}
export async function api<T>(
  path: string,
  user: User,
  signal: AbortSignal,
  init: RequestInit = {},
): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set("X-Demo-User", user);
  const response = await fetch(path, {
    ...init,
    headers,
    signal,
    cache: "no-store",
  });
  if (!response.ok) {
    const error = await response.json().catch(() => null);
    throw new ApiError(
      response.status,
      error?.message ?? "请求失败，请稍后重试",
    );
  }
  return response.json();
}
export async function imageBlob(
  path: string,
  user: User,
  signal: AbortSignal,
): Promise<Blob> {
  const response = await fetch(path, {
    headers: { "X-Demo-User": user },
    signal,
    cache: "no-store",
  });
  if (!response.ok) throw new ApiError(response.status, "图片暂时无法加载");
  return response.blob();
}
export const isActive = (task: Task) =>
  task.status === "QUEUED" || task.status === "RUNNING";
export const stageNames: Record<Stage, string> = {
  NONE: "等待开始",
  CHECK_MATERIAL: "素材检查",
  GENERATE: "模拟生成",
  FINALIZE: "结果整理",
};

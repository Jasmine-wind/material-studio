"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import {
  api,
  stageNames,
  type Asset,
  type Stage,
  type Template,
} from "@/lib/api";
import { useUser } from "./providers";
import { UploadSlot } from "./upload-slot";

export function Maker({ template }: { template: Template }) {
  const user = useUser();
  const client = useQueryClient();
  const [slots, setSlots] = useState<{ asset: Asset | null; busy: boolean }[]>(
    Array.from({ length: template.requiredImageCount }, () => ({
      asset: null,
      busy: false,
    })),
  );
  const [simulationEnabled, setSimulationEnabled] = useState(false);
  const [failStage, setFailStage] = useState("");
  const [delayMs, setDelayMs] = useState(2000);
  const [accepted, setAccepted] = useState("");
  const controller = useRef<AbortController | null>(null);
  useEffect(() => () => controller.current?.abort(), []);
  const change = useCallback(
    (index: number, asset: Asset | null, busy: boolean) => {
      setSlots((old) =>
        old.map((slot, i) => (i === index ? { asset, busy } : slot)),
      );
    },
    [],
  );
  const mutation = useMutation({
    mutationFn: async () => {
      controller.current = new AbortController();
      return api<{ id: string; status: string }>(
        "/api/tasks",
        user,
        controller.current.signal,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            templateId: template.id,
            assetIds: slots.map((s) => s.asset?.id),
            simulation: simulationEnabled
              ? { failStage: failStage || null, delayMs }
              : undefined,
          }),
        },
      );
    },
    onSuccess: (data) => {
      setAccepted(data.id);
      void client.invalidateQueries({ queryKey: ["tasks", user] });
    },
  });
  const ready = slots.every((s) => s.asset && !s.busy);
  return (
    <section className="maker" aria-label="素材制作">
      <div className="section-heading">
        <h2>
          <span className="step-number">01</span> 上传你的素材
        </h2>
        <span className="small muted">
          {slots.filter((s) => s.asset).length} / {template.requiredImageCount}{" "}
          张
        </span>
      </div>
      <p className="muted small">
        需要 {template.requiredImageCount} 张图片 · PNG / JPEG · 每张不超过 10MB
      </p>
      <div
        className="upload-grid"
        style={
          { "--slots": template.requiredImageCount } as React.CSSProperties
        }
      >
        {slots.map((_, i) => (
          <UploadSlot
            key={i}
            index={i}
            template={template}
            disabled={mutation.isPending}
            onChange={change}
          />
        ))}
      </div>
      <details className="simulation">
        <summary>
          演示设置 <span>本地模拟 · 非真实 AI</span>
        </summary>
        <label className="simulation-toggle">
          <input
            type="checkbox"
            checked={simulationEnabled}
            onChange={(e) => setSimulationEnabled(e.target.checked)}
            disabled={mutation.isPending}
          />{" "}
          启用演示参数（仅演示环境）
        </label>
        <div className="simulation-controls">
          <label>
            执行结果
            <select
              aria-label="模拟失败阶段"
              value={failStage}
              onChange={(e) => setFailStage(e.target.value)}
              disabled={mutation.isPending}
            >
              <option value="">全部成功</option>
              {(["CHECK_MATERIAL", "GENERATE", "FINALIZE"] as Stage[]).map(
                (stage) => (
                  <option key={stage} value={stage}>
                    {stageNames[stage]}失败
                  </option>
                ),
              )}
            </select>
          </label>
          <label>
            每阶段耗时（毫秒）
            <input
              aria-label="每阶段耗时"
              type="number"
              min={0}
              max={10000}
              step={100}
              value={delayMs}
              disabled={mutation.isPending}
              onChange={(e) => setDelayMs(e.target.valueAsNumber)}
            />
          </label>
        </div>
      </details>
      {mutation.isError && (
        <div className="error" role="alert">
          提交未成功：{mutation.error.message}{" "}
          <button
            onClick={() =>
              void client.invalidateQueries({ queryKey: ["tasks", user] })
            }
          >
            同步历史
          </button>
        </div>
      )}
      {accepted && (
        <div className="accepted" role="status">
          ✓ 已受理 #{accepted.slice(0, 8)}
          ，进度请查看制作记录。你可以继续创建新任务。
        </div>
      )}
      <button
        className="primary create-button"
        disabled={
          !ready ||
          mutation.isPending ||
          (simulationEnabled &&
            (!Number.isFinite(delayMs) || delayMs < 0 || delayMs > 10000))
        }
        onClick={() => {
          setAccepted("");
          mutation.mutate();
        }}
      >
        {mutation.isPending ? "正在提交…" : "✦ 开始制作"}
        <span>→</span>
      </button>
      <p className="small muted center">
        提交后由后台独立制作，无需停留在此页面。
      </p>
    </section>
  );
}

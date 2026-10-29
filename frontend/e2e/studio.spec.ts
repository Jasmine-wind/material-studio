import {
  test,
  expect,
  type APIRequestContext,
  type Page,
} from "@playwright/test";
import { readFile } from "node:fs/promises";
import path from "node:path";
const sample = (n: number) =>
  path.join(process.cwd(), `public/demo/sample-${n}.png`);
const headers = (owner = "alice") => ({ "X-Demo-User": owner });
async function upload(request: APIRequestContext, owner = "alice", n = 1) {
  const response = await request.post("/api/assets", {
    headers: headers(owner),
    multipart: {
      file: {
        name: `sample-${n}.png`,
        mimeType: "image/png",
        buffer: await readFile(sample(n)),
      },
    },
  });
  expect(response.status()).toBe(201);
  return response.json();
}
async function waitTask(request: APIRequestContext, id: string) {
  await expect
    .poll(
      async () =>
        (
          await (
            await request.get(`/api/tasks/${id}`, { headers: headers() })
          ).json()
        ).status,
      { timeout: 30000, intervals: [100, 250, 500] },
    )
    .toMatch(/SUCCEEDED|FAILED/);
  return (await request.get(`/api/tasks/${id}`, { headers: headers() })).json();
}
test("real API / MySQL: async contract, ordered persisted stages, failure points, 1/2/3 assets and isolation", async ({
  request,
}, info) => {
  const list = await (
    await request.get("/api/templates", { headers: headers() })
  ).json();
  expect(list).toHaveLength(6);
  expect(new Set(list.map((t: { category: string }) => t.category)).size).toBe(
    3,
  );
  for (const template of list)
    expect((await request.get(template.coverUrl)).ok()).toBeTruthy();
  expect((await request.get("/api/templates")).status()).toBe(400);
  expect(
    (
      await request.get("/api/templates/not-found", { headers: headers() })
    ).status(),
  ).toBe(404);
  const assets = await Promise.all(
    [1, 2, 3].map((n) => upload(request, "alice", n)),
  );
  expect(
    (
      await request.get(assets[0].previewUrl, { headers: headers("bob") })
    ).status(),
  ).toBe(404);
  expect(
    (
      await request.post("/api/tasks", {
        headers: headers("bob"),
        data: { templateId: "lunar", assetIds: [assets[0].id] },
      })
    ).status(),
  ).toBe(404);
  expect(
    (
      await request.post("/api/tasks", {
        headers: headers(),
        data: { templateId: "bloom", assetIds: [assets[0].id] },
      })
    ).status(),
  ).toBe(400);
  expect(
    (
      await request.post("/api/assets", {
        headers: headers(),
        multipart: {
          file: {
            name: "spoof.png",
            mimeType: "image/png",
            buffer: Buffer.from("not a PNG"),
          },
        },
      })
    ).status(),
  ).toBe(400);
  const start = Date.now();
  const accepted = await request.post("/api/tasks", {
    headers: headers(),
    data: {
      templateId: "lunar",
      assetIds: [assets[0].id],
      simulation: { delayMs: 800 },
    },
  });
  expect(accepted.status()).toBe(202);
  expect(Date.now() - start).toBeLessThan(1500);
  const first = await accepted.json();
  expect(first.status).toBe("QUEUED");
  expect(first.stage).toBe("NONE");
  expect(
    (
      await request.get(`/api/tasks/${first.id}/result`, { headers: headers() })
    ).status(),
  ).toBe(409);
  const stages: string[] = [];
  for (let i = 0; i < 150; i++) {
    const current = await (
      await request.get(`/api/tasks/${first.id}`, { headers: headers() })
    ).json();
    if (current.stage !== "NONE" && stages.at(-1) !== current.stage)
      stages.push(current.stage);
    if (current.status === "SUCCEEDED") break;
    await new Promise((r) => setTimeout(r, 50));
  }
  expect(stages).toEqual(["CHECK_MATERIAL", "GENERATE", "FINALIZE"]);
  expect((await waitTask(request, first.id)).status).toBe("SUCCEEDED");
  const result = await request.get(`/api/tasks/${first.id}/result`, {
    headers: headers(),
  });
  expect(result.status()).toBe(200);
  expect(await result.body()).toEqual(await readFile(sample(1)));
  for (const endpoint of [
    `/api/tasks/${first.id}`,
    `/api/tasks/${first.id}/result`,
  ])
    expect(
      (await request.get(endpoint, { headers: headers("bob") })).status(),
    ).toBe(404);
  const jobs = [];
  for (const [templateId, count, failStage] of [
    ["dune", 2, null],
    ["bloom", 3, null],
    ["lunar", 1, "CHECK_MATERIAL"],
    ["lunar", 1, "GENERATE"],
    ["lunar", 1, "FINALIZE"],
  ] as const) {
    const response = await request.post("/api/tasks", {
      headers: headers(),
      data: {
        templateId,
        assetIds: assets.slice(0, count).map((a) => a.id),
        simulation: { failStage, delayMs: 100 },
      },
    });
    expect(response.status()).toBe(202);
    jobs.push({ ...(await response.json()), failStage });
  }
  const records = [];
  for (const job of jobs) {
    const terminal = await waitTask(request, job.id);
    expect(terminal.status).toBe(job.failStage ? "FAILED" : "SUCCEEDED");
    expect(terminal.failedStage).toBe(job.failStage);
    if (job.failStage) expect(terminal.stage).toBe(job.failStage);
    expect(terminal.finishedAt).toBeTruthy();
    records.push(terminal);
  }
  // Querying terminal tasks never restarts them.
  expect((await waitTask(request, first.id)).status).toBe("SUCCEEDED");
  await info.attach("api-terminal-records", {
    body: JSON.stringify(records, null, 2),
    contentType: "application/json",
  });
});

async function configure(page: Page, stage: string, delay = 300) {
  await page
    .locator("details.simulation")
    .evaluate((el: HTMLDetailsElement) => {
      el.open = true;
    });
  await page.getByLabel("启用演示参数（仅演示环境）").check();
  await page.getByLabel("模拟失败阶段").selectOption(stage);
  await page.getByLabel("每阶段耗时").fill(String(delay));
}
async function submit(page: Page) {
  const response = page.waitForResponse(
    (r) => r.url().endsWith("/api/tasks") && r.request().method() === "POST",
  );
  await page.getByRole("button", { name: /开始制作/ }).click();
  const accepted = await response;
  expect(accepted.status()).toBe(202);
  const data = await accepted.json();
  await expect(
    page.getByText(`✓ 已受理 #${data.id.slice(0, 8)}`, { exact: false }),
  ).toBeVisible();
  await expect(page.getByRole("button", { name: /开始制作/ })).toBeEnabled();
  return data.id as string;
}

test("browser: filter, 1/2/3 uploads, multiple jobs, refresh recovery, identity switch, query errors and responsive UI", async ({
  page,
}, info) => {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto("/");
  await expect(page.locator(".template-card")).toHaveCount(6);
  await page.screenshot({
    path: info.outputPath("desktop-templates.png"),
    fullPage: true,
  });
  await page.getByRole("button", { name: "旅行手记", exact: true }).click();
  await expect(page.locator(".template-card")).toHaveCount(2);
  await page.getByRole("button", { name: "全部灵感", exact: true }).click();
  const ids: string[] = [];
  for (const [templateId, count] of [
    ["lunar", 1],
    ["dune", 2],
    ["bloom", 3],
  ] as const) {
    await page.goto(`/templates/${templateId}`);
    await expect(page.getByRole("button", { name: /开始制作/ })).toBeDisabled();
    for (let n = 1; n <= count; n++)
      await page
        .getByLabel(`选择素材 ${n}`, { exact: true })
        .setInputFiles(sample(n));
    await expect(page.getByText("✓ 上传完成", { exact: true })).toHaveCount(
      count,
    );
    await configure(page, "", 800);
    ids.push(await submit(page));
    if (templateId === "lunar") {
      // Same uploaded material can create another independent job.
      await configure(page, "GENERATE", 500);
      ids.push(await submit(page));
      await page.screenshot({
        path: info.outputPath("desktop-maker.png"),
        fullPage: true,
      });
      await page.reload();
      await expect(page.getByTestId(`task-${ids[0]}`)).toBeVisible();
    }
  }
  // Exercise the other two deterministic failure controls through the actual UI.
  await page.goto("/templates/lunar");
  await page.getByLabel("选择素材 1", { exact: true }).setInputFiles(sample(1));
  await expect(page.getByText("✓ 上传完成", { exact: true })).toBeVisible();
  for (const stage of ["CHECK_MATERIAL", "FINALIZE"]) {
    await configure(page, stage, 200);
    ids.push(await submit(page));
  }
  await page.goto("/history");
  for (const id of ids)
    await expect(page.getByTestId(`task-${id}`)).toContainText(
      /已完成|制作失败/,
      { timeout: 30000 },
    );
  const success = page.getByTestId(`task-${ids[0]}`);
  await expect(success.getByRole("img")).toBeVisible();
  expect(
    await success
      .getByRole("img")
      .evaluate((img: HTMLImageElement) => img.naturalWidth),
  ).toBeGreaterThan(0);
  await expect(page.getByTestId(`task-${ids[1]}`)).toContainText(
    "模拟生成失败",
  );
  await page.reload();
  await expect(success).toContainText("已完成");
  await page.getByLabel("演示用户").selectOption("bob");
  for (const id of ids)
    await expect(page.getByTestId(`task-${id}`)).toHaveCount(0);
  await page.reload();
  await expect(page.getByLabel("演示用户")).toHaveValue("bob");
  await page.getByLabel("演示用户").selectOption("alice");
  await expect(success).toContainText("已完成");
  // A transport failure must preserve server business state.
  await page.route("**/api/tasks", (route) =>
    route.request().method() === "GET"
      ? route.fulfill({
          status: 503,
          contentType: "application/json",
          body: '{"message":"temporary failure"}',
        })
      : route.continue(),
  );
  await page.getByRole("button", { name: /手动刷新/ }).click();
  await expect(page.locator("main").getByRole("alert")).toContainText(
    "状态暂时无法同步",
  );
  await expect(success).toContainText("已完成");
  await page.unroute("**/api/tasks");
  await page.getByRole("button", { name: "重试", exact: true }).click();
  await expect(page.locator("main").getByRole("alert")).toHaveCount(0);
  await page.screenshot({
    path: info.outputPath("desktop-history.png"),
    fullPage: true,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/templates/bloom");
  await expect(
    page.getByRole("heading", { name: "花间序曲", exact: true }),
  ).toBeVisible();
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth),
  ).toBeLessThanOrEqual(390);
  await page.screenshot({
    path: info.outputPath("mobile-maker.png"),
    fullPage: true,
  });
  await page.goto("/templates/no-such-template");
  await expect(page.getByRole("heading", { name: "模板不存在" })).toBeVisible();
  expect(errors).toEqual([]);
});

import { test, expect } from "@playwright/test";
import { readFile } from "node:fs/promises";
import path from "node:path";

test("Review Gate: unsupported media is 415 and rendered stage labels meet AA", async ({
  request,
  page,
}, info) => {
  const headers = { "X-Demo-User": "alice" };
  const unsupported = await request.post("/api/tasks", {
    headers: { ...headers, "Content-Type": "text/plain" },
    data: "{}",
  });
  expect(unsupported.status()).toBe(415);
  expect((await unsupported.json()).code).toBe("UNSUPPORTED_MEDIA_TYPE");
  const upload = await request.post("/api/assets", {
    headers,
    multipart: {
      file: {
        name: "contrast.png",
        mimeType: "image/png",
        buffer: await readFile(
          path.join(process.cwd(), "public/demo/sample-1.png"),
        ),
      },
    },
  });
  expect(upload.status()).toBe(201);
  const asset = await upload.json();
  const created = await request.post("/api/tasks", {
    headers,
    data: {
      templateId: "lunar",
      assetIds: [asset.id],
      simulation: { delayMs: 1500 },
    },
  });
  expect(created.status()).toBe(202);
  const { id } = await created.json();
  await page.goto("/templates/lunar");
  const card = page.getByTestId(`task-${id}`);
  await expect(card.locator(".stage-track")).toBeVisible();
  const ratios = await card
    .locator(".stage-track > div")
    .evaluateAll((elements) => {
      const luminance = (color: string) => {
        const c = color
          .match(/[\d.]+/g)!
          .slice(0, 3)
          .map(Number)
          .map((n) => n / 255)
          .map((n) =>
            n <= 0.04045 ? n / 12.92 : ((n + 0.055) / 1.055) ** 2.4,
          );
        return c[0] * 0.2126 + c[1] * 0.7152 + c[2] * 0.0722;
      };
      return elements.map((element) => {
        const color = getComputedStyle(element).color;
        const background = getComputedStyle(
          element.closest(".task-card")!,
        ).backgroundColor;
        const [low, high] = [luminance(color), luminance(background)].sort(
          (a, b) => a - b,
        );
        return {
          label: element.textContent,
          color,
          background,
          ratio: (high + 0.05) / (low + 0.05),
        };
      });
    });
  expect(ratios).toHaveLength(3);
  for (const stage of ratios)
    expect(stage.ratio, stage.label ?? "stage").toBeGreaterThanOrEqual(4.5);
  await info.attach("rendered-stage-contrast", {
    body: JSON.stringify(ratios, null, 2),
    contentType: "application/json",
  });
  await page.screenshot({ path: info.outputPath("stage-contrast.png") });
  await expect
    .poll(
      async () =>
        (await (await request.get(`/api/tasks/${id}`, { headers })).json())
          .status,
      { timeout: 15000 },
    )
    .toBe("SUCCEEDED");
});

import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { ProtectedImage } from "./protected-image";
let user = "alice";
vi.mock("./providers", () => ({ useUser: () => user }));
afterEach(() => {
  user = "alice";
  vi.unstubAllGlobals();
});
it("fetches binary images with the user header, revokes object URLs on user change and unmount", async () => {
  URL.createObjectURL = vi
    .fn()
    .mockReturnValueOnce("blob:alice")
    .mockReturnValueOnce("blob:bob");
  URL.revokeObjectURL = vi.fn();
  const fetcher = vi.fn(() => Promise.resolve(new Response("image")));
  vi.stubGlobal("fetch", fetcher);
  const view = render(
    <ProtectedImage path="/api/tasks/one/result" alt="作品" />,
  );
  expect(await screen.findByAltText("作品")).toHaveAttribute(
    "src",
    "blob:alice",
  );
  expect(fetcher).toHaveBeenCalledWith(
    "/api/tasks/one/result",
    expect.objectContaining({
      headers: { "X-Demo-User": "alice" },
      signal: expect.any(AbortSignal),
    }),
  );
  user = "bob";
  view.rerender(<ProtectedImage path="/api/tasks/one/result" alt="作品" />);
  await waitFor(() =>
    expect(screen.getByAltText("作品")).toHaveAttribute("src", "blob:bob"),
  );
  expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:alice");
  view.unmount();
  expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:bob");
});

import { NextRequest } from "next/server";
export const dynamic = "force-dynamic";
async function proxy(request: NextRequest) {
  const url = new URL(
    request.nextUrl.pathname + request.nextUrl.search,
    process.env.BACKEND_URL ?? "http://localhost:8080",
  );
  const headers = new Headers();
  for (const name of ["content-type", "x-demo-user"]) {
    const value = request.headers.get(name);
    if (value) headers.set(name, value);
  }
  try {
    const response = await fetch(url, {
      method: request.method,
      headers,
      signal: request.signal,
      cache: "no-store",
      body: request.method === "POST" ? await request.arrayBuffer() : undefined,
    });
    const outgoing = new Headers({ "Cache-Control": "no-store" });
    for (const name of ["content-type", "x-content-type-options"]) {
      const value = response.headers.get(name);
      if (value) outgoing.set(name, value);
    }
    return new Response(response.body, {
      status: response.status,
      headers: outgoing,
    });
  } catch {
    return Response.json(
      { code: "BACKEND_UNAVAILABLE", message: "状态暂时无法同步，请稍后重试" },
      { status: 502 },
    );
  }
}
export const GET = proxy;
export const POST = proxy;

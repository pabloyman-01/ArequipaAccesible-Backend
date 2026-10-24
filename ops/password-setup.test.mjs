import test from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import { passwordSetupServer } from "./password-setup.mjs";

const target = {
  id: "11111111-1111-4111-8111-111111111111",
  email: "owner@example.test",
  confirmado: true,
  administrador: true,
  estado_cuenta: "ACTIVA",
};
const password = "FixturePassword123!"; // Test fixture only, never an actual account password.
async function fixture(t, override) {
  const calls = [],
    sessions = [],
    proofs = [];
  const fetcher = async (url, options) => {
    calls.push({ path: new URL(url).pathname, options });
    if (override) {
      const response = await override(url, options);
      if (response) return response;
    }
    if (options.method === "PUT") return Response.json({ id: target.id });
    if (options.method === "POST")
      return Response.json({
        user: { id: target.id },
        access_token: "fixture-token",
      });
    return Response.json({
      ...target,
      email_confirmed_at: "2026-01-01T00:00:00Z",
    });
  };
  const secret = "sb_secret_fixture_server_key";
  const server = passwordSetupServer({
    target,
    project: "https://identity.example.test",
    secret,
    publishable: "sb_publishable_fixture",
    fetcher,
    saveSession: async (s) => sessions.push(s),
    saveProof: async (p) => proofs.push(p),
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  t.after(
    () =>
      new Promise((resolve) => {
        server.closeAllConnections();
        server.close(resolve);
      }),
  );
  const origin = "http://127.0.0.1:" + server.address().port;
  const page = await fetch(origin);
  const html = await page.text();
  const nonce = html.match(/script nonce="([a-f0-9]+)"/)[1];
  const send = (data = { password, confirmation: password }, headers = {}) =>
    fetch(origin + "/password", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Origin: origin,
        "X-Setup-Nonce": nonce,
        ...headers,
      },
      body: JSON.stringify(data),
    });
  return { calls, sessions, proofs, origin, html, page, send, secret };
}
test("page is private, uncacheable and exposes no server key", async (t) => {
  const f = await fixture(t);
  assert.equal(f.page.headers.get("cache-control"), "no-store");
  assert.match(
    f.page.headers.get("content-security-policy"),
    /frame-ancestors 'none'/,
  );
  assert(!f.html.includes(f.secret));
  assert(!f.html.includes("fixture-token"));
  const status = await new Promise((resolve, reject) => {
    http
      .get(f.origin, { headers: { Host: "attacker.example" } }, (res) => {
        res.resume();
        resolve(res.statusCode);
      })
      .on("error", reject);
  });
  assert.equal(status, 403);
});
test("cross-origin or missing nonce never reaches Supabase", async (t) => {
  const f = await fixture(t);
  assert.equal(
    (await f.send(undefined, { Origin: "https://attacker.example" })).status,
    403,
  );
  assert.equal(
    (await f.send(undefined, { "X-Setup-Nonce": "wrong" })).status,
    403,
  );
  assert.equal(f.calls.length, 0);
});
test("client cannot change target email or submit mismatched passwords", async (t) => {
  const f = await fixture(t);
  assert.equal(
    (await f.send({ password, confirmation: "different" })).status,
    400,
  );
  assert.equal(
    (
      await f.send({
        password,
        confirmation: password,
        email: "other@example.test",
      })
    ).status,
    400,
  );
  assert.equal(f.calls.length, 0);
});
test("updates only the approved password once and never returns credentials to the browser", async (t) => {
  const f = await fixture(t);
  const result = await f.send();
  const text = await result.text();
  assert.equal(result.status, 200);
  assert.equal(JSON.parse(text).loginVerified, true);
  assert(!text.includes(password));
  assert(!text.includes("fixture-token"));
  const put = f.calls.find((c) => c.options.method === "PUT");
  assert.equal(put.path, "/auth/v1/admin/users/" + target.id);
  assert.deepEqual(JSON.parse(put.options.body), { password });
  assert.equal(f.sessions.length, 1);
  assert(!JSON.stringify(f.proofs).includes(password));
  assert(!JSON.stringify(f.proofs).includes("fixture-token"));
  assert.equal((await f.send()).status, 409);
  assert.equal(f.calls.filter((c) => c.options.method === "PUT").length, 1);
  assert(
    f.calls.every(
      (c) => !c.path.endsWith("/otp") && !c.path.endsWith("/recover"),
    ),
  );
});
test("revalidates the target before changing anything", async (t) => {
  const f = await fixture(t, (_url, options) =>
    !options.method
      ? Response.json({
          ...target,
          email: "other@example.test",
          email_confirmed_at: "2026-01-01T00:00:00Z",
        })
      : undefined,
  );
  assert.equal((await f.send()).status, 409);
  assert.equal(f.calls.length, 1);
  assert.equal(f.sessions.length, 0);
});
test("ambiguous update response is not retried and is not announced as success", async (t) => {
  const f = await fixture(t, (_url, options) => {
    if (options.method === "PUT") throw new Error("simulated connection loss");
  });
  assert.equal((await f.send()).status, 502);
  assert.equal(f.proofs[0].passwordUpdated, "unknown");
  assert.equal((await f.send()).status, 409);
  assert.equal(f.calls.filter((c) => c.options.method === "PUT").length, 1);
});
test("a failed login check does not undo or repeat a successful password update", async (t) => {
  const f = await fixture(t, (_url, options) =>
    options.method === "POST"
      ? Response.json({ error: "fixture" }, { status: 429 })
      : undefined,
  );
  const result = await f.send();
  assert.equal(result.status, 200);
  assert.equal((await result.json()).loginVerified, false);
  assert.equal(f.proofs[0].passwordUpdated, true);
  assert.equal((await f.send()).status, 409);
  assert.equal(f.sessions.length, 0);
});

if (!globalThis.RUTONGO_API_BASE) {
  const api = new URLSearchParams(globalThis.location.search).get("api");
  if (api) globalThis.RUTONGO_API_BASE = api;
}

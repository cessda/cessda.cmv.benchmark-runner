// Replace all fetch() calls with apiFetch() — identical API, adds the key header.
function apiFetch(url, opts = {}) {
  const key = sessionStorage.getItem('apiKey');
  if (!key) {
    // First visit — prompt once and store for the session
    const entered = prompt('Enter your API key:');
    if (!entered) throw new Error('API key required');
    sessionStorage.setItem('apiKey', entered);
    return apiFetch(url, opts); // retry with key now in storage
  }
  return fetch(url, {
    ...opts,
    headers: { ...(opts.headers || {}), 'X-API-Key': key }
  });
}
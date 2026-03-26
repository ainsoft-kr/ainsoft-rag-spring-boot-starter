const jsonHeaders = {
  'Content-Type': 'application/json'
};

async function parseResponse(response) {
  const contentType = response.headers.get('content-type') ?? '';
  const isJson = contentType.includes('application/json');
  const payload = isJson ? await response.json() : await response.text();

  if (!response.ok) {
    const message =
      (typeof payload === 'object' && payload !== null && 'message' in payload && payload.message) ||
      response.statusText ||
      'Request failed';
    throw new Error(String(message));
  }

  return payload;
}

export async function apiGet(path) {
  const response = await fetch(path);
  return parseResponse(response);
}

export async function apiPost(path, body) {
  const response = await fetch(path, {
    method: 'POST',
    headers: jsonHeaders,
    body: JSON.stringify(body)
  });
  return parseResponse(response);
}

export async function apiUpload(path, formData) {
  const response = await fetch(path, {
    method: 'POST',
    body: formData
  });
  return parseResponse(response);
}

import { afterEach, describe, expect, it, vi } from 'vitest';

import { ApiError, apiRequest } from './client';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('apiRequest', () => {
  it('throws ApiError carrying the ErrorResponse body', async () => {
    const body = {
      code: 'PRODUCT_NOT_FOUND',
      message: 'Not found',
      traceId: 'abc12345',
      timestamp: '2026-01-01T00:00:00Z',
    };
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(JSON.stringify(body), { status: 404 })),
    );

    const error = await apiRequest('/products/x').catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(404);
    expect((error as ApiError).code).toBe('PRODUCT_NOT_FOUND');
  });

  it('prefixes the API base path', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response('{"ok":true}', { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(apiRequest('/me')).resolves.toEqual({ ok: true });
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/me', expect.anything());
  });
});

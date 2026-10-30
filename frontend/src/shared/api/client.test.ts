import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError, apiRequest, setSessionExpiredHandler } from './client';
import type { components } from './schema';
import { clearSession, getAccessToken, getRefreshToken, setSession } from './tokenStore';

type TokenResponse = components['schemas']['TokenResponse'];

function tokens(access: string, refresh: string): TokenResponse {
  return {
    accessToken: access,
    refreshToken: refresh,
    tokenType: 'Bearer',
    expiresIn: 900,
    user: {
      id: 1,
      email: 'a@b.c',
      fullName: 'A',
      role: 'CUSTOMER',
      createdAt: '2026-01-01T00:00:00Z',
    },
  };
}

function errorBody(code: string, extra: Record<string, unknown> = {}) {
  return {
    code,
    message: `msg ${code}`,
    traceId: 'abc12345',
    timestamp: '2026-01-01T00:00:00Z',
    ...extra,
  };
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function authHeader(call: unknown[]): string | null {
  return new Headers((call[1] as RequestInit).headers).get('Authorization');
}

beforeEach(() => {
  clearSession();
  setSessionExpiredHandler(undefined);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('apiRequest', () => {
  it('throws ApiError carrying the ErrorResponse body', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json(errorBody('PRODUCT_NOT_FOUND'), 404)));

    const error = await apiRequest('/products/x').catch((e: unknown) => e);

    expect(ApiError.isApiError(error)).toBe(true);
    const apiError = error as ApiError;
    expect(apiError.status).toBe(404);
    expect(apiError.code).toBe('PRODUCT_NOT_FOUND');
    expect(apiError.traceId).toBe('abc12345');
    expect(apiError.fieldErrors).toEqual([]);
  });

  it('prefixes the API base path', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json({ ok: true }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(apiRequest('/me')).resolves.toEqual({ ok: true });
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/me', expect.anything());
  });

  it('TC-1 attaches bearer token only when a session exists and auth is not disabled', async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(json({})));
    vi.stubGlobal('fetch', fetchMock);

    await apiRequest('/me');
    expect(authHeader(fetchMock.mock.calls[0] as unknown[])).toBeNull();

    setSession(tokens('A', 'R'));
    await apiRequest('/me');
    expect(authHeader(fetchMock.mock.calls[1] as unknown[])).toBe('Bearer A');

    await apiRequest('/me', { auth: false });
    expect(authHeader(fetchMock.mock.calls[2] as unknown[])).toBeNull();
  });

  it('TC-2 exposes fieldErrors from a 422 ErrorResponse', async () => {
    const fieldErrors = [{ field: 'email', message: 'invalid' }];
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(json(errorBody('VALIDATION_ERROR', { fieldErrors }), 422)),
    );

    const error = (await apiRequest('/x').catch((e: unknown) => e)) as ApiError;

    expect(error.status).toBe(422);
    expect(error.code).toBe('VALIDATION_ERROR');
    expect(error.fieldErrors).toEqual(fieldErrors);
  });

  it('TC-3 falls back to a generic message for non-JSON error bodies', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response('<html>Bad gateway</html>', {
          status: 502,
          headers: { 'Content-Type': 'text/html' },
        }),
      ),
    );

    const error = (await apiRequest('/x').catch((e: unknown) => e)) as ApiError;

    expect(error.status).toBe(502);
    expect(error.body).toBeUndefined();
    expect(error.message).toBe('Request failed with status 502');
  });

  it('TC-4 wraps a rejected fetch as ApiError(0) Network error', async () => {
    const cause = new TypeError('Failed to fetch');
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(cause));

    const error = (await apiRequest('/x').catch((e: unknown) => e)) as ApiError;

    expect(ApiError.isApiError(error)).toBe(true);
    expect(error.status).toBe(0);
    expect(error.message).toBe('Network error');
    expect(error.cause).toBe(cause);
  });

  it('TC-5 refreshes once on 401 TOKEN_EXPIRED and retries with the new token', async () => {
    setSession(tokens('A', 'R'));
    const fetchMock = vi.fn().mockImplementation((url: string, init: RequestInit) => {
      if (url === '/api/v1/auth/refresh') {
        expect(JSON.parse(init.body as string)).toEqual({ refreshToken: 'R' });
        return Promise.resolve(json(tokens('B', 'R2')));
      }
      const bearer = new Headers(init.headers).get('Authorization');
      return Promise.resolve(
        bearer === 'Bearer B' ? json({ ok: true }) : json(errorBody('TOKEN_EXPIRED'), 401),
      );
    });
    vi.stubGlobal('fetch', fetchMock);

    await expect(apiRequest('/me')).resolves.toEqual({ ok: true });

    const urls = fetchMock.mock.calls.map((c) => c[0] as string);
    expect(urls.filter((u) => u === '/api/v1/auth/refresh')).toHaveLength(1);
    expect(urls).toHaveLength(3);
    expect(getAccessToken()).toBe('B');
    expect(getRefreshToken()).toBe('R2');
  });

  it('TC-6 shares one refresh across concurrent 401 TOKEN_EXPIRED requests', async () => {
    setSession(tokens('A', 'R'));
    const fetchMock = vi.fn().mockImplementation(async (url: string, init: RequestInit) => {
      if (url === '/api/v1/auth/refresh') {
        await Promise.resolve();
        return json(tokens('B', 'R2'));
      }
      const bearer = new Headers(init.headers).get('Authorization');
      return bearer === 'Bearer B' ? json({ ok: url }) : json(errorBody('TOKEN_EXPIRED'), 401);
    });
    vi.stubGlobal('fetch', fetchMock);

    const results = await Promise.all([apiRequest('/a'), apiRequest('/b'), apiRequest('/c')]);

    expect(results).toEqual([{ ok: '/api/v1/a' }, { ok: '/api/v1/b' }, { ok: '/api/v1/c' }]);
    const urls = fetchMock.mock.calls.map((c) => c[0] as string);
    expect(urls.filter((u) => u === '/api/v1/auth/refresh')).toHaveLength(1);
    expect(urls).toHaveLength(7);
  });

  it('TC-7 clears the session and notifies once when refresh fails', async () => {
    setSession(tokens('A', 'R'));
    const handler = vi.fn();
    setSessionExpiredHandler(handler);
    const fetchMock = vi
      .fn()
      .mockImplementation((url: string) =>
        Promise.resolve(
          url === '/api/v1/auth/refresh'
            ? json(errorBody('INVALID_TOKEN'), 401)
            : json(errorBody('TOKEN_EXPIRED'), 401),
        ),
      );
    vi.stubGlobal('fetch', fetchMock);

    const error = (await apiRequest('/me').catch((e: unknown) => e)) as ApiError;

    expect(error.status).toBe(401);
    expect(error.code).toBe('TOKEN_EXPIRED');
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(handler).toHaveBeenCalledTimes(1);
    expect(getAccessToken()).toBeNull();
    expect(getRefreshToken()).toBeNull();
  });

  it('TC-8 does not refresh on INVALID_TOKEN or on /auth/ paths', async () => {
    setSession(tokens('A', 'R'));
    const fetchMock = vi.fn().mockResolvedValueOnce(json(errorBody('INVALID_TOKEN'), 401));
    fetchMock.mockResolvedValueOnce(json(errorBody('TOKEN_EXPIRED'), 401));
    vi.stubGlobal('fetch', fetchMock);

    const first = (await apiRequest('/me').catch((e: unknown) => e)) as ApiError;
    const second = (await apiRequest('/auth/login', { method: 'POST' }).catch(
      (e: unknown) => e,
    )) as ApiError;

    expect(first.code).toBe('INVALID_TOKEN');
    expect(second.status).toBe(401);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('TC-9 returns undefined for 204', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 204 })));

    await expect(apiRequest('/x', { method: 'DELETE' })).resolves.toBeUndefined();
  });
});

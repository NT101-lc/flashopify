import type { components } from './schema';
import { clearSession, getAccessToken, getRefreshToken, setSession } from './tokenStore';

export type ErrorResponse = components['schemas']['ErrorResponse'];
export type ErrorCode = components['schemas']['ErrorCode'];
type TokenResponse = components['schemas']['TokenResponse'];
type RefreshTokenRequest = components['schemas']['RefreshTokenRequest'];

const BASE_URL = '/api/v1';

/** Non-2xx response from the API, carrying the parsed ErrorResponse body when there is one. */
export class ApiError extends Error {
  readonly status: number;
  readonly body: ErrorResponse | undefined;

  constructor(status: number, body: ErrorResponse | undefined, message?: string, cause?: unknown) {
    super(
      message ?? body?.message ?? `Request failed with status ${status}`,
      cause === undefined ? undefined : { cause },
    );
    this.name = 'ApiError';
    this.status = status;
    this.body = body;
  }

  get code(): ErrorCode | undefined {
    return this.body?.code;
  }

  get traceId(): string | undefined {
    return this.body?.traceId;
  }

  get fieldErrors(): NonNullable<ErrorResponse['fieldErrors']> {
    return this.body?.fieldErrors ?? [];
  }

  static isApiError(error: unknown): error is ApiError {
    return error instanceof ApiError;
  }
}

export type ApiRequestInit = RequestInit & {
  /** Attach the access token when one exists. Defaults to true. */
  auth?: boolean;
};

export function isErrorResponse(value: unknown): value is ErrorResponse {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const v = value as Record<string, unknown>;
  return (
    typeof v.code === 'string' &&
    typeof v.message === 'string' &&
    typeof v.traceId === 'string' &&
    typeof v.timestamp === 'string'
  );
}

let sessionExpiredHandler: (() => void) | undefined;

/** Registers the callback invoked when the session cannot be refreshed (e.g. redirect to login). */
export function setSessionExpiredHandler(handler: (() => void) | undefined): void {
  sessionExpiredHandler = handler;
}

let refreshInFlight: Promise<void> | undefined;

/** Single-flight: concurrent callers share one POST /auth/refresh (and one failure handling). */
function refreshSession(refreshToken: string): Promise<void> {
  refreshInFlight ??= (async () => {
    try {
      const request: RefreshTokenRequest = { refreshToken };
      const tokens = await send<TokenResponse>('/auth/refresh', {
        method: 'POST',
        body: JSON.stringify(request),
        auth: false,
      });
      setSession(tokens);
    } catch (error) {
      clearSession();
      sessionExpiredHandler?.();
      throw error;
    } finally {
      refreshInFlight = undefined;
    }
  })();
  return refreshInFlight;
}

async function toApiError(response: Response): Promise<ApiError> {
  let body: ErrorResponse | undefined;
  if ((response.headers.get('content-type') ?? '').includes('json')) {
    const parsed: unknown = await response.json().catch(() => undefined);
    body = isErrorResponse(parsed) ? parsed : undefined;
  }
  return new ApiError(response.status, body);
}

async function send<T>(path: string, init: ApiRequestInit): Promise<T> {
  const { auth = true, ...fetchInit } = init;
  const headers = new Headers(fetchInit.headers);
  headers.set('Accept', 'application/json');
  if (fetchInit.body !== undefined && !(fetchInit.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json');
  }
  const token = getAccessToken();
  if (auth && token) {
    headers.set('Authorization', `Bearer ${token}`);
  }

  let response: Response;
  try {
    response = await fetch(`${BASE_URL}${path}`, { ...fetchInit, headers });
  } catch (cause) {
    throw new ApiError(0, undefined, 'Network error', cause);
  }
  if (!response.ok) {
    throw await toApiError(response);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

/**
 * Single entry point for HTTP calls. Feature `api/` modules wrap it and feature `hooks/` expose it
 * through TanStack Query; components never call this (or fetch) directly.
 * Attaches the bearer token and, on 401 TOKEN_EXPIRED, refreshes once and retries once.
 */
export async function apiRequest<T>(path: string, init: ApiRequestInit = {}): Promise<T> {
  try {
    return await send<T>(path, init);
  } catch (error) {
    const refreshToken = getRefreshToken();
    if (
      !ApiError.isApiError(error) ||
      error.status !== 401 ||
      error.code !== 'TOKEN_EXPIRED' ||
      init.auth === false ||
      path.startsWith('/auth/') ||
      !refreshToken
    ) {
      throw error;
    }
    try {
      await refreshSession(refreshToken);
    } catch {
      throw error;
    }
    return send<T>(path, init);
  }
}

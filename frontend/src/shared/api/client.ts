import type { components } from './schema';

export type ErrorResponse = components['schemas']['ErrorResponse'];
export type ErrorCode = components['schemas']['ErrorCode'];

const BASE_URL = '/api/v1';

/** Non-2xx response from the API, carrying the parsed ErrorResponse body when there is one. */
export class ApiError extends Error {
  readonly status: number;
  readonly body: ErrorResponse | undefined;

  constructor(status: number, body: ErrorResponse | undefined) {
    super(body?.message ?? `Request failed with status ${status}`);
    this.name = 'ApiError';
    this.status = status;
    this.body = body;
  }

  get code(): ErrorCode | undefined {
    return this.body?.code;
  }
}

/**
 * Single entry point for HTTP calls. Feature `api/` modules wrap it and feature `hooks/` expose it
 * through TanStack Query; components never call this (or fetch) directly.
 */
export async function apiRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set('Accept', 'application/json');
  if (init.body !== undefined && !(init.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json');
  }

  const response = await fetch(`${BASE_URL}${path}`, { ...init, headers });
  if (!response.ok) {
    const body = (await response.json().catch(() => undefined)) as ErrorResponse | undefined;
    throw new ApiError(response.status, body);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

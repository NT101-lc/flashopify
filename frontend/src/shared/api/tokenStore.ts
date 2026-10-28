import type { components } from './schema';

type TokenResponse = components['schemas']['TokenResponse'];

const REFRESH_TOKEN_KEY = 'flashopify.refreshToken';

// The access token lives only in memory so it is not readable after a page reload and is a smaller
// XSS target. The refresh token is persisted in localStorage so a reload can restore the session;
// this is readable by any injected script (XSS trade-off). Moving it to an httpOnly cookie is a
// future backend change.
let accessToken: string | null = null;
const listeners = new Set<() => void>();

function writeRefreshToken(token: string | null): void {
  try {
    if (token === null) {
      localStorage.removeItem(REFRESH_TOKEN_KEY);
    } else {
      localStorage.setItem(REFRESH_TOKEN_KEY, token);
    }
  } catch {
    // Storage unavailable (private mode / quota): the session just won't survive a reload.
  }
}

function notify(): void {
  listeners.forEach((listener) => listener());
}

export function getAccessToken(): string | null {
  return accessToken;
}

export function getRefreshToken(): string | null {
  try {
    return localStorage.getItem(REFRESH_TOKEN_KEY);
  } catch {
    return null;
  }
}

export function setSession(tokens: TokenResponse): void {
  accessToken = tokens.accessToken;
  writeRefreshToken(tokens.refreshToken);
  notify();
}

export function clearSession(): void {
  accessToken = null;
  writeRefreshToken(null);
  notify();
}

export function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

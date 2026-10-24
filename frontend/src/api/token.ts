/**
 * The short-lived access token lives only in memory: a script injected into the
 * page cannot read it from storage, and it disappears when the tab closes. The
 * long-lived refresh token is an HttpOnly cookie the browser handles by itself.
 */
let accessToken: string | null = null;

export const token = {
  get: () => accessToken,
  set: (value: string | null) => {
    accessToken = value;
  },
};

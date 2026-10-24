import { toApiError } from "./errors";

interface Result<T> {
  data?: T;
  error?: unknown;
  response: Response;
}

/** Returns the data of a successful call, or throws an ApiError with the server's explanation. */
export async function unwrap<T>(call: Promise<Result<T>>): Promise<T> {
  const { data, error, response } = await call;
  if (data === undefined) throw toApiError(response.status, error);
  return data;
}

/** For calls that succeed with no body (204). */
export async function expectOk(call: Promise<Result<unknown>>): Promise<void> {
  const { error, response } = await call;
  if (!response.ok) throw toApiError(response.status, error);
}

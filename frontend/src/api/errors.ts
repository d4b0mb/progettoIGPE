/** The shape of an error the API sends back (RFC 9457 "problem details"). */
export interface Problem {
  status?: number;
  detail?: string;
  fields?: Record<string, string>;
}

export class ApiError extends Error {
  readonly status: number;
  readonly fields: Record<string, string>;

  constructor(status: number, detail: string | undefined, fields: Record<string, string> = {}) {
    super(detail ?? `Request failed (${status})`);
    this.name = "ApiError";
    this.status = status;
    this.fields = fields;
  }
}

/** Turns whatever the API returned for a failed call into an ApiError. */
export function toApiError(status: number, body: unknown): ApiError {
  const problem = (typeof body === "object" && body !== null ? body : {}) as Problem;
  return new ApiError(status, problem.detail, problem.fields ?? {});
}

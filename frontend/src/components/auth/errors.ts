import { ApiError } from "@/api/errors";

/** What a sign-in or sign-up form should show for a failed call. */
export interface FormProblem {
  message: string;
  fields: Record<string, string>;
}

export function describeAuthError(
  error: unknown,
  t: (key: "invalidCredentials" | "emailTaken") => string,
  generic: string,
): FormProblem {
  if (error instanceof ApiError) {
    if (error.status === 401) return { message: t("invalidCredentials"), fields: {} };
    if (error.status === 409) return { message: t("emailTaken"), fields: {} };
    if (error.status === 400 && Object.keys(error.fields).length > 0) return { message: "", fields: error.fields };
  }
  return { message: generic, fields: {} };
}

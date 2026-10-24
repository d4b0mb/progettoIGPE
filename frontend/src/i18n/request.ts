import { cookies } from "next/headers";
import { getRequestConfig } from "next-intl/server";

export const LOCALES = ["it", "en"] as const;
export type Locale = (typeof LOCALES)[number];
export const LOCALE_COOKIE = "locale";

/**
 * The interface language comes from a cookie the user sets with the language
 * switch. Italian is the default, since the first restaurants are Italian.
 */
export default getRequestConfig(async () => {
  const stored = (await cookies()).get(LOCALE_COOKIE)?.value;
  const locale: Locale = stored === "en" ? "en" : "it";
  return {
    locale,
    messages: (await import(`../../messages/${locale}.json`)).default,
  };
});

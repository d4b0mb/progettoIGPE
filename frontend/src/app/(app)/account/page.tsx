import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";
import { AccountScreen } from "@/components/account/AccountScreen";

export async function generateMetadata(): Promise<Metadata> {
  return { title: (await getTranslations("account"))("title") };
}

export default function AccountPage() {
  return <AccountScreen />;
}

import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";
import { RecoverFlow } from "@/components/auth/RecoverFlow";

export async function generateMetadata(): Promise<Metadata> {
  return { title: (await getTranslations("recover"))("title") };
}

export default function RecoverPage() {
  return <RecoverFlow />;
}

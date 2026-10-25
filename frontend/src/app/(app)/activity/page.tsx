import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";
import { ActivityScreen } from "@/components/activity/ActivityScreen";

export async function generateMetadata(): Promise<Metadata> {
  return { title: (await getTranslations("activity"))("title") };
}

export default function ActivityPage() {
  return <ActivityScreen />;
}

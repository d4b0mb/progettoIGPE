import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";
import { FloorScreen } from "@/components/floor/FloorScreen";

export async function generateMetadata(): Promise<Metadata> {
  return { title: (await getTranslations("floor"))("title") };
}

export default function FloorPage() {
  return <FloorScreen />;
}

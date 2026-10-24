import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";
import { MenuScreen } from "@/components/menu/MenuScreen";

export async function generateMetadata(): Promise<Metadata> {
  return { title: (await getTranslations("menu"))("title") };
}

export default function MenuPage() {
  return <MenuScreen />;
}

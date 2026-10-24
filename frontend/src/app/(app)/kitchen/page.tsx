import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";
import { KitchenScreen } from "@/components/orders/KitchenScreen";

export async function generateMetadata(): Promise<Metadata> {
  return { title: (await getTranslations("kitchen"))("title") };
}

export default function KitchenPage() {
  return <KitchenScreen />;
}

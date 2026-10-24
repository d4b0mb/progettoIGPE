import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";
import { OrdersScreen } from "@/components/orders/OrdersScreen";

export async function generateMetadata(): Promise<Metadata> {
  return { title: (await getTranslations("orders"))("title") };
}

export default function OrdersPage() {
  return <OrdersScreen />;
}

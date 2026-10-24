import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";
import { ReservationsScreen } from "@/components/reservations/ReservationsScreen";

export async function generateMetadata(): Promise<Metadata> {
  return { title: (await getTranslations("reservations"))("title") };
}

export default function ReservationsPage() {
  return <ReservationsScreen />;
}

import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";
import { LoginForm } from "@/components/auth/LoginForm";

export async function generateMetadata(): Promise<Metadata> {
  return { title: (await getTranslations("auth"))("signIn") };
}

export default async function LoginPage({ searchParams }: { searchParams: Promise<{ [key: string]: string | string[] | undefined }> }) {
  // Only the fixed code "expired" is understood; any other value is ignored. The text shown is our own translation.
  const { reason } = await searchParams;
  return <LoginForm sessionEnded={reason === "expired"} />;
}

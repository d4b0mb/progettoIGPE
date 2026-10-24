import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";
import { LoginForm } from "@/components/auth/LoginForm";

export async function generateMetadata(): Promise<Metadata> {
  return { title: (await getTranslations("auth"))("signIn") };
}

export default function LoginPage() {
  return <LoginForm />;
}

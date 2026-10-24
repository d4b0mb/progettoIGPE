import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";
import { SignupForm } from "@/components/auth/SignupForm";

export async function generateMetadata(): Promise<Metadata> {
  return { title: (await getTranslations("auth"))("signUp") };
}

export default function SignupPage() {
  return <SignupForm />;
}

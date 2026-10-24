import type { IconName } from "@/components/ui/Icon";
import type { components } from "@/api/schema";

export type Role = components["schemas"]["UserView"]["role"];

export interface NavItem {
  href: string;
  /** Key in the "nav" translations. */
  key: "reservations" | "floor" | "orders" | "kitchen" | "menu" | "staff";
  icon: IconName;
  /** Who sees this entry. The server still checks the role on every request. */
  roles: Role[];
}

/** Every screen of the back-office, and who may open it. Screens are added here as they are built. */
export const NAV: NavItem[] = [
  { href: "/reservations", key: "reservations", icon: "calendar", roles: ["OWNER", "MANAGER", "WAITER"] },
  { href: "/staff", key: "staff", icon: "users", roles: ["OWNER"] },
];

export function navFor(role: Role): NavItem[] {
  return NAV.filter((item) => item.roles.includes(role));
}

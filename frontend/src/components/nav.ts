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
  { href: "/orders", key: "orders", icon: "receipt", roles: ["OWNER", "MANAGER", "WAITER"] },
  { href: "/kitchen", key: "kitchen", icon: "flame", roles: ["OWNER", "MANAGER", "WAITER", "KITCHEN"] },
  { href: "/floor", key: "floor", icon: "floor", roles: ["OWNER", "MANAGER", "WAITER"] },
  { href: "/menu", key: "menu", icon: "book", roles: ["OWNER", "MANAGER", "WAITER"] },
  { href: "/staff", key: "staff", icon: "users", roles: ["OWNER"] },
];

export function navFor(role: Role): NavItem[] {
  return NAV.filter((item) => item.roles.includes(role));
}

/**
 * Whether this role may open a path. A path that is not in the menu (the home page) is open to everyone
 * signed in. This only decides what to show; the server checks the role on every request regardless.
 */
export function mayOpen(role: Role, pathname: string): boolean {
  const item = NAV.find((n) => pathname === n.href || pathname.startsWith(`${n.href}/`));
  return item ? item.roles.includes(role) : true;
}

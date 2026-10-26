# Trying it in a real pizzeria (Duodecim Pizze)

A low-risk trial next to the normal way of working. The cashier PC keeps doing receipts and payments;
this app only handles orders, the kitchen screen and (optionally) reservations and the floor plan.
Run it in parallel with paper or the usual system for the first services.

## What runs where
| Device | Job |
|--------|-----|
| Your laptop (not the cashier PC) | The server: `docker compose up -d` from this repo |
| Samsung tablet | The kitchen screen (log in as the kitchen account) |
| Phones | Waiters take orders (waiter accounts) |
| Any browser | Owner/manager: menu, floor plan, reservations, activity log |

Everything is reached at `http://<laptop address>:3000` on the pizzeria's Wi-Fi.

## Before the day (at home)
1. `git pull`, then `docker compose up --build -d`. The stack creates two demo restaurants. **Duodecim Pizze**
   already has the printed menu (98 items), a placeholder room with 8 tables and the green/red look.
   Logins: `owner@`, `manager@`, `waiter@`, `kitchen@` + `duodecim.ristomanager.local`, password `Demo-Password-2026`.
2. Check the menu screen against the printed menu. **The allergens are a draft** worked out from the ingredient
   lists, not confirmed by the kitchen. Someone who knows the recipes must go through them (the dish form has
   an allergen picker) before anyone relies on the allergy warnings.
3. Draw the real room in the Floor plan screen (add the tables with their real numbers and seats).
4. Try a full order on your phone: two servings, the kitchen account on a second window.

## On the day
1. Connect the laptop to the pizzeria Wi-Fi. Find its address: `ipconfig`, "IPv4 Address" (like 192.168.1.20).
2. Allow the port once (PowerShell as administrator):
   `New-NetFirewallRule -DisplayName "RistoManager" -Direction Inbound -Protocol TCP -LocalPort 3000 -Action Allow -Profile Private`
   (Windows asks whether the network is private or public: choose private.)
3. On the tablet open `http://192.168.1.20:3000`, sign in as the kitchen account, and choose
   Chrome menu (three dots) > Add to Home screen so it opens like an app.
4. Tablet settings so the screen stays on: Settings > Display > Screen timeout > longest; keep it charging;
   Settings > Apps > Chrome > Battery > Unrestricted.
5. Keep the laptop plugged in, with sleep turned off (Power settings > Sleep: Never).

### If the tablet cannot reach the laptop
Some Wi-Fi networks stop devices from talking to each other ("client isolation"). Test this before service.
Fallback that needs nothing from the router: on the laptop turn on Windows "Mobile hotspot", connect the
tablet and phones to it, and use the address the laptop shows for itself on that hotspot (usually 192.168.137.1).

## Which login is which
Two restaurants live in the system. **Duodecim Pizze** uses `owner@`, `manager@`, `waiter@`, `kitchen@` + `duodecim.ristomanager.local`.
The `demo.ristomanager.local` logins belong to a different restaurant ("Trattoria Demo") and see none of the pizzeria's data.
The name under the logo in the menu shows which restaurant you are in.

## How an order works
- The waiter picks the table, taps dishes, and can split the order into **servings** ("Portata 1, 2, 3"):
  tap "Add serving", then add dishes to it. Example: antipasti in serving 1, pizzas in serving 2, dessert in 3.
- The kitchen sees serving 1 in full and the rest as "coming next". They tap Start, then Mark ready.
- The waiter taps "Serving 1 of 3 served". The order stays, and serving 2 now appears as new in the kitchen.
- **Toppings and notes:** each dish has a Customise button (pizzas) or Add note button (everything else). Tick the toppings or removals
  (Bufala in cottura, Senza basilico, Non tagliata...), write anything else ("senza pepe", "con ghiaccio") and add it. Priced toppings add to
  the estimate; toppings with allergens count in the kitchen's allergy alarm. The kitchen sees `+ Bufala in cottura` and the note on the ticket.
- The search box and the course buttons on the order screen find a dish among the ~100 on the menu.

## Not covered (keep using the cashier PC for these)
Receipts and fiscal printing, payments, takeaway and delivery, splitting the bill, printing tickets, stock.
The prices in the app are for reference; **it is not a fiscal till**.

## Guests' data
Do not type real guests' phone numbers or emails during the trial unless the owner agrees. All data stays on the
laptop. It can be erased completely afterwards with `docker compose down -v`. Guest details in the app are
blanked automatically after 90 days (see `docs/DATA_RETENTION.md`).

## After the trial
Write down what slowed people down. The most useful notes: a dish that was hard to find, a moment the screen
was too small, anything the kitchen asked for that was missing.

// Fills the demo restaurants with a week of reservations and tonight's orders, so the screens have
// something going on. Everything goes through the API, so the normal rules apply (no double bookings,
// no past days). A day that already has this script's bookings is skipped, so running it twice is safe.
// Usage: node scripts/seed-week.mjs   (API_URL defaults to http://localhost:8080)

const API = process.env.API_URL ?? "http://localhost:8080";
const PASSWORD = process.env.DEMO_PASSWORD ?? "Demo-Password-2026";
const TAG = "demo week";
const ZONE_OFFSET = "+01:00"; // Europe/Rome in winter time; the week after 25 October 2026 is all CET

const RESTAURANTS = [
  { owner: "owner@demo.ristomanager.local", lunch: 4, dinner: 5, tonight: 3 },
  { owner: "owner@duodecim.ristomanager.local", lunch: 5, dinner: 10, tonight: 6 },
];

const NAMES = ["Rossi", "Russo", "Ferrari", "Esposito", "Bianchi", "Romano", "Colombo", "Ricci", "Marino", "Greco",
  "Bruno", "Gallo", "Conti", "De Luca", "Mancini", "Costa", "Giordano", "Rizzo", "Lombardi", "Moretti", "Barbieri",
  "Fontana", "Santoro", "Mariani", "Rinaldi", "Caruso", "Ferrara", "Galli", "Martini", "Leone"];
const ALLERGENS = ["GLUTEN", "MILK", "EGGS", "TREE_NUTS", "FISH", "MOLLUSCS", "SULPHITES"];
const NOTES = ["", "", "", "Compleanno", "Tavolo vicino alla finestra", "Cliente abituale", "Arrivano in ritardo"];

let seed = 20261026;
const rand = () => ((seed = (seed * 1103515245 + 12345) % 2 ** 31) / 2 ** 31);
const pick = (list) => list[Math.floor(rand() * list.length)];
const between = (lo, hi) => lo + Math.floor(rand() * (hi - lo + 1));

async function call(token, method, path, body) {
  const res = await fetch(API + path, {
    method,
    headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${path} -> HTTP ${res.status} ${text.slice(0, 200)}`);
  return text ? JSON.parse(text) : null;
}

const pad = (n) => String(n).padStart(2, "0");
const dayString = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const at = (day, hour, minute) => `${day}T${pad(hour)}:${pad(minute)}:00${ZONE_OFFSET}`;

function guest() {
  return {
    guestName: pick(NAMES),
    phone: rand() < 0.8 ? `+39 3${between(10, 49)} ${between(100, 999)} ${between(1000, 9999)}` : "",
    allergens: rand() < 0.25 ? [pick(ALLERGENS)] : [],
    notes: [pick(NOTES), TAG].filter(Boolean).join(" · "),
  };
}

/** Books one party on a free table, or returns null when the room is full at that time. */
async function book(token, startsAt, status) {
  const partySize = pick([2, 2, 2, 3, 4, 4, 5, 6]);
  const free = await call(token, "GET",
    `/api/v1/reservations/available-tables?startsAt=${encodeURIComponent(startsAt)}&partySize=${partySize}`);
  if (free.length === 0) return null;
  return call(token, "POST", "/api/v1/reservations", {
    ...guest(), startsAt, partySize, durationMinutes: 120, tableId: pick(free).id, status,
    highChairs: rand() < 0.1 ? 1 : 0, strollerSpaces: 0,
  });
}

/** A believable order: a drink or two, then food from the courses this menu has. */
function orderLines(menu, partySize) {
  const byCourse = Object.groupBy(menu, (item) => item.course);
  const lines = [];
  if (byCourse.DRINK) lines.push({ menuItemId: pick(byCourse.DRINK).id, quantity: between(1, partySize) });
  const food = Object.keys(byCourse).filter((course) => course !== "DRINK");
  for (let i = 0; i < Math.min(partySize, 4); i++) {
    lines.push({ menuItemId: pick(byCourse[pick(food)]).id, quantity: 1, note: rand() < 0.15 ? "Ben cotta" : null });
  }
  return lines;
}

async function seedRestaurant({ owner, lunch, dinner, tonight }) {
  const { accessToken: token } = await call(null, "POST", "/api/v1/auth/login", { email: owner, password: PASSWORD });
  const menu = (await call(token, "GET", "/api/v1/menu/items")).filter((item) => item.available);
  const today = new Date();
  let booked = 0;
  let orders = 0;

  for (let offset = 0; offset < 7; offset++) {
    const day = dayString(new Date(today.getFullYear(), today.getMonth(), today.getDate() + offset));
    const existing = await call(token, "GET", `/api/v1/reservations?date=${day}`);
    if (existing.some((r) => r.notes?.includes(TAG))) {
      console.log(`  ${day}: already seeded, skipped`);
      continue;
    }
    let count = 0;
    if (offset === 0) {
      // Tonight: parties already seated (started within the last hour), each with an order on the go.
      const now = new Date();
      for (let i = 0; i < tonight; i++) {
        const start = new Date(now.getTime() - between(10, 60) * 60_000);
        const startsAt = at(day, start.getHours(), start.getMinutes());
        const party = await book(token, startsAt, "ARRIVED");
        if (!party) continue;
        count++;
        const ticket = await call(token, "POST", "/api/v1/orders", {
          tableId: party.tableId, reservationId: party.id, priority: rand() < 0.2 ? "HIGH" : "NORMAL",
          note: null, items: orderLines(menu, party.partySize),
        });
        orders++;
        // A spread across the kitchen screen: some new, some cooking, some ready, some already served.
        const steps = [[], ["PREPARING"], ["PREPARING", "READY"], ["PREPARING", "READY", "SERVED"]][i % 4];
        for (const status of steps) await call(token, "PATCH", `/api/v1/orders/${ticket.id}/status`, { status });
      }
    } else {
      for (let i = 0; i < lunch; i++) {
        if (await book(token, at(day, between(12, 13), pick([0, 15, 30, 45])), pick(["CONFIRMED", "CONFIRMED", "PENDING"]))) count++;
      }
      for (let i = 0; i < dinner; i++) {
        if (await book(token, at(day, between(19, 21), pick([0, 15, 30, 45])), pick(["CONFIRMED", "CONFIRMED", "PENDING"]))) count++;
      }
    }
    booked += count;
    console.log(`  ${day}: ${count} reservations`);
  }
  console.log(`${owner}: ${booked} reservations, ${orders} orders`);
}

for (const restaurant of RESTAURANTS) await seedRestaurant(restaurant);

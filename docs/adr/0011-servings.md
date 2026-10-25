# 0011. Servings: the kitchen gets an order one serving at a time

Status: Accepted

## Decision
Every line of an order has a serving number (1 to 9, default 1). An order stays one ticket. The ticket
remembers which serving is being cooked now (`current_serving`) and when that serving reached the kitchen
(`serving_since`). Its status (NEW, PREPARING, READY) is the status of that serving. When the waiter marks the
ready serving as served and later servings exist, the ticket does not finish: the next serving becomes current
and is NEW again. The last serving finishes the ticket. Cancelling stops the whole order.

## Why
Guests order antipasti, then pizza, then dessert; the kitchen should cook each at the right moment, and the order
must not be lost or duplicated between servings. Keeping one ticket means the existing status endpoint, the live
stream, the daily code (C-001) and the allergy checks work unchanged. An order with a single serving behaves exactly as before.

## Trade-offs
- Servings are strictly in order: the kitchen cannot start serving 2 early. If that is needed later, add a
  "send now" action that makes a chosen serving current.
- Times (late flag) count from when the serving reached the kitchen, not from when the order was taken.
- Serving numbers may have gaps (1 and 3); the screens show "Serving 2 of 2" by position.

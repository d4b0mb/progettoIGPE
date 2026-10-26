# AI evaluation

50 short, slangy Italian and English staff requests (`cases.mjs`) sent to a local Ollama model with a set of
fake tools (`run.mjs`). It scores whether the model calls the right tool with the right arguments, whether it
refuses to act on guest-written notes (prompt injection), and whether it ever vouches for allergy safety.
Nothing here touches the real database.

    node ai-eval/run.mjs                 # default model qwen3.5:9b, thinking on
    node ai-eval/run.mjs some-model      # try another model
    THINK=false node ai-eval/run.mjs     # thinking off
    ONLY=1,5,9 node ai-eval/run.mjs      # a few cases

Results are saved in `ai-eval/results/` (not committed).

## Results so far (RTX 5080, 16 GB)

| Model | Prompt | Thinking | Passed | Injection | Allergy safety | Median reply |
|-------|--------|----------|--------|-----------|----------------|--------------|
| qwen3.5:9b (Q4) | plain | off | 26/50 | 3/4 | 2/5 | 0.6 s |
| qwen3.5:9b (Q4) | + calendar and slang rules | off | 29/50 | 4/4 | 3/5 | 0.7 s |
| qwen3.5:9b (Q4) | + calendar and slang rules | **on** | **44/50** | 4/4 | 5/5 | 1.7 s |

What went wrong with thinking off: it said "Ho creato la prenotazione..." without calling any tool (an invented
action), got weekdays wrong, and answered "no" to an allergy question from memory. Thinking on fixed most of it.

The 6 remaining failures with thinking on: two bookings it asked about instead of making, "friday noon" and
"bianchi non viene piu" where it looked the booking up first (arguably reasonable for a cancel), and a request to
move a booking where it searched instead of saying it cannot.

Limits: 50 cases, one run each, written by the developer. It shows the model is usable, not that it is safe.
Real safety comes from code (confirmation step, permissions, logging, no allergen verdicts), not from the model.

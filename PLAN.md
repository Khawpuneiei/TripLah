# TripLah: plan and feasibility check

## Context

TripLah is a trip planner for overseas students making short trips (1 day to 1 week). It comes from three real problems of mine:

1. Fly to an award ceremony/onsite hackathon for one day and be back before class or lab. Cheapest option that fits my timetable.
2. Bring my girlfriend to an onsite hackathon abroad. Cheap, fits both timetables, and covers the stay if the organiser doesn't.
3. Bring my family to Singapore, then fly back to Thailand together.

It is also my portfolio project for Agoda's Back-end GPT Tools internship (Python, Kotlin, RAG, Skills, MCP). The tool plans trips only. It never books or pays.

## Is it possible?

Five of the six features are possible. One part of the sixth is not possible as asked, and gets a replacement.

| # | Feature | Verdict | What limits it |
|---|---|---|---|
| 1 | Security and guardrails | ✅ Yes | Fully in our control |
| 2 | Fast mode | ✅ Yes | Free flight APIs take a few seconds per search; last-minute fares are rarely cheap |
| 3 | Low-cost mode (wait for a lower price) | ✅ Price calendar and price watch. ⚠️ "Buy or wait" advice is a rough estimate only | Good price prediction needs months of price history |
| 4 | Low-cost convenient (capsule near the venue) | ✅ On seed data. ⚠️ Real capsule and hostel coverage is unverified | Depends on the hotel API |
| 5 | Family mode | ✅ Yes. ⚠️ Room size and lift data is missing for some hotels | Hotels with missing data are shown as "unknown", not hidden |
| 6a | Recheck for cancelled or retimed flights | ✅ Yes, by polling a flight-status API | Free tiers allow few calls, so only watched flights are checked |
| 6b | "May cancel because few seats are booked" | ❌ Not possible as asked | Airlines don't publish seats sold per flight |
| 6c | Replacement: a cancellation risk signal | ⚠️ Possible as Low / Medium / High | Built from history, not from bookings |

Why 6b fails: research shows emptier flights do get cancelled more often, but public data only gives load factor per route, never per flight. Seat maps exist for some airlines, but a blocked seat is not a sold seat.

## One planner, four modes

Each mode is a set of hard rules plus a ranking order. The planner code is shared.

| Mode | Use when | Hard rules | Ranked by |
|---|---|---|---|
| `FAST` | Trip starts within about 72 hours | Fits the timetable; direct flights first; returns one best plan and one backup | Earliest workable plan, then price |
| `LOW_COST` | Dates are flexible | Fits the timetable; carry-on only; red-eye flights and one stop allowed | Lowest total price across all free windows |
| `LOW_COST_CONVENIENT` | Hackathon or event | Capsule or hostel bed within 15 minutes of the venue; late check-in; lockers (if the hotel data doesn't list them: shown as unknown, ranked lower) | Price plus minutes to the venue |
| `FAMILY` | Parents and grandparents | Private room, 25 m² or more or a family room; lift; short walk to MRT; no red-eye; no stops; checked bag; everyone on one booking | Comfort first, then price |

### Timetable rules (all modes)

- Import a timetable as an `.ics` file (NTU or Google Calendar export) or as JSON. One timetable per traveller.
- Each event is tagged **must-attend** (lab, exam) or **can-skip** (lecture).
- A plan never breaks a must-attend event. If no zero-conflict plan exists, the tool shows the plan with the fewest can-skip hours missed and says which classes those are.
- Buffers are settings: campus to airport, check-in before departure, immigration on arrival.
- All times are stored with their timezone (Singapore is UTC+8, Bangkok is UTC+7).

## How each pain point is solved

| Pain point | Mode | What the planner does |
|---|---|---|
| 1. One-day award ceremony | `FAST` or `LOW_COST` | Finds flights that arrive before the ceremony (with buffer), leave after it ends, and land back before the next must-attend class |
| 2. Hackathon with girlfriend | `LOW_COST_CONVENIENT` | Intersects both timetables; searches for 2 travellers; if the organiser doesn't cover the stay, compares 2 capsule pods against 1 private twin room; splits the cost |
| 3. Family to Singapore, fly back together | `FAMILY` | Leg 1: family flies BKK→SIN. Stay: family-mode hotel for N nights. Leg 2: everyone flies SIN→BKK on the same flight, searched as one booking for N seats |

For leg 2 the search uses the real passenger count, because the price for N seats on one booking can differ from N single seats.

## Feature details

### Fast mode
- Search and ranking are plain Kotlin code. The LLM only reads the request and explains the result.
- Providers are called in parallel (coroutines) with a 5-second timeout each.
- Results are cached for 10–15 minutes. Airports and timetables are cached longer.
- Targets: under 1 second on seed data, under 10 seconds on real providers.

### Low-cost mode
- **Price calendar:** cheapest price for every free window in the semester, from cached prices.
- **Price watch:** save a target price. The watcher checks once or twice a day and alerts when the price drops below it.
- **Book-by date:** every watch has a deadline, so waiting can't end with no ticket.
- **Buy or wait:** a simple rule (days to departure, price against the route's median in our own history), labelled as an estimate.

### Flight recheck and risk signal
- **Recheck:** watched flights are polled daily, every 6 hours in the last week, and hourly in the last 24 hours.
- If the time moves by 15 minutes or more, or the flight is cancelled, the planner re-runs the timetable check. The alert names the class it now clashes with and offers alternatives.
- **Risk signal:** Low / Medium / High, with reasons: this flight's cancellation and delay rate over the last 30–90 days, flights per day on the route, last flight of the day, airline type.
- The wording is always "risk based on history". It never says a flight will be cancelled, and it never claims to know seats sold.

### Security and guardrails
- Plan only: no booking, no payment, no card data. Output is a booking link.
- No passport or ID numbers are stored. Travellers are "Traveller A / B". Timetables stay local.
- Tool inputs are validated (airport codes, dates, 1–9 passengers, budget range).
- Outbound calls go only to an allow-list of provider hosts, with timeouts and a per-provider quota guard so free tiers aren't used up.
- Provider text (hotel descriptions, reviews) is treated as data. Tools return structured fields, and tests inject "ignore previous instructions" into hotel text.
- Every price, time and flight number in an answer must exist in a tool result. The eval checks this.
- Every offer carries `fetched_at` and a source. Old offers are re-checked before they are shown as final.
- `FAMILY` mode refuses self-transfers between separate tickets and warns about late-night arrivals.
- Secrets live in environment variables. CI runs a secret scan.
- Official APIs only. No scraping.

## Architecture

```
Claude Code + SKILL.md "student-trip-planner"
   │ MCP (stdio)
Kotlin MCP server ──► Ktor REST API (+ OpenAPI spec) ──► planner-core (pure Kotlin)
                                │                             time windows, rules, scoring
                                ├─ FlightProvider   Mock | SerpApi | Travelpayouts
                                ├─ StayProvider     Mock | LiteAPI
                                ├─ StatusProvider   Mock | AeroDataBox or FlightAware
                                ├─ TransitProvider  Mock | OneMap (Singapore)
                                └─ SQLite: watches, price history, flight history
Watcher (scheduled) ──► price watch, flight recheck ──► alert (Telegram or email)
Python FastAPI RAG (stretch) ──► travel rules with cited sources
```

**Repo layout**

```
triplah/
  planner-core/   pure Kotlin, no network: windows, rules, scoring
  providers/      the four provider interfaces, mock and real adapters
  api/            Ktor REST API and OpenAPI spec
  mcp-server/     Kotlin MCP server
  watcher/        scheduled checks and alerts
  rules-rag/      Python FastAPI (stretch)
  skill/SKILL.md
  seed/           flights, stays, events (6 cities)
  evals/          prompts and expected results
```

**MCP tools**

| Tool | Does |
|---|---|
| `import_timetable` | Loads an `.ics` or JSON timetable for one traveller |
| `find_free_windows` | Free windows shared by all travellers, with buffers |
| `search_flights` / `search_stays` | Raw search through the providers |
| `plan_trip` | Full plan for a pain point, mode, travellers and budget |
| `split_budget` | Cost per person |
| `watch_price` | Saves a target price and book-by date |
| `check_flight` | Current status and any time change |
| `flight_risk` | Low / Medium / High with reasons |
| `ask_travel_rules` | Baggage, refund and entry rules with a cited source (stretch) |

## Data sources (checked 6 Nov 2026)

Free-tier numbers below come mostly from third-party pages. Confirm each on the official pricing page at sign-up.

| Need | Source | Cost | Notes |
|---|---|---|---|
| Tests and evals | Own seed data, 6 cities | Free | Repeatable; used in CI |
| Live flight prices | SerpApi Google Flights | Free plan, about 250 searches a month | Includes low-cost airlines; unconfirmed whether flight searches count the same |
| Price calendar | Travelpayouts Aviasales Data API | Free with a token | Cached prices, up to 7 days old |
| Flight status | AeroDataBox | Small free tier (size unconfirmed) | Has flight-alert webhooks |
| Flight status (alternative) | FlightAware AeroAPI Personal | Up to $5 of free usage a month | Personal or academic use only; alerts may need a paid tier |
| Hotels | LiteAPI sandbox | Free sandbox key | Has its own MCP server; capsule coverage and room-size fields unverified |
| Singapore transit | OneMap routing API | Free with a token | Public-transport routing |
| Ruled out | Amadeus Self-Service | — | Was scheduled to shut down 17 July 2026 |
| Ruled out for prices | Duffel | Test mode free | Test prices are fake; live mode charges if searches exceed 1,500 per booking |

## Build phases

The six features do not fit in 2 days. Phase 0 is the part to finish before applying.

| Phase | Time | Delivers |
|---|---|---|
| **0** | 2 days | Pain point 1 on seed data: `planner-core` with timetable windows, `FAST` and `LOW_COST`, `.ics` import, Ktor API, MCP server with 4 tools, `SKILL.md`, 20 eval cases, CI |
| 1 | 3–4 days | Pain point 2: multi-timetable, stays, `LOW_COST_CONVENIENT`, cost split |
| 2 | 3–4 days | Pain point 3: `FAMILY` mode, multi-leg group trips, OneMap transit |
| 3 | About 1 week | Watcher: price watch, flight recheck, risk signal, alerts |
| 4 | Stretch | One real flight provider, LiteAPI stays, rules RAG, comparison with Agoda's `api-agent`, small React page |

## Verification

- **Unit tests (`planner-core`):** SIN/BKK timezone cases, buffer edges, two-timetable intersection, must-attend events never broken, every `FAMILY` hard rule.
- **Provider tests:** recorded responses only, no live calls in CI.
- **Eval, 30 prompts (10 per pain point):** a plan passes if it has zero must-attend conflicts, stays within budget, meets its mode's rules, and every price and flight appears in a tool result.
- **Guardrail tests:** injected text in a hotel description, invalid inputs, quota guard trips before the free tier runs out.
- **Recheck test:** the mock status provider moves a flight by 2 hours and then cancels it; the alert names the clashing class and offers an alternative.
- **Speed:** p50 and p95 per mode against the Fast-mode targets.
- **Manual check:** run pain point 1 for a real date on a real provider and compare with Google Flights.

## Open risks

1. Live flight prices on a free plan are limited, so caching and the quota guard are required from the start.
2. Capsule coverage and room size in LiteAPI are unverified. Check with a sandbox key before Phase 1.
3. The risk signal needs flight history. It starts weak and improves as the watcher collects data.
4. Entry and visa answers can go out of date. They always cite a source and tell the user to check the official site.

## Status (6 Nov 2026)

All phases are built and tested on seed data. See [README.md](README.md) for how to run it.

| Phase | Status |
|---|---|
| 0 | Done: `planner-core`, `FAST` and `LOW_COST`, `.ics` + JSON import, Ktor API + OpenAPI, MCP server (11 tools), `SKILL.md`, 30 eval cases, CI |
| 1 | Done: two-timetable intersection, stays, `LOW_COST_CONVENIENT`, pods vs twin comparison, `split_budget` |
| 2 | Done: `FAMILY` mode, 3-leg family visit (leg 2 is one booking for everyone), OneMap adapter |
| 3 | Done: watcher (price watch with book-by date, flight recheck on the daily / 6-hourly / hourly schedule), risk signal, alerts (console, SQLite, Telegram) |
| 4 | Built: real adapters (SerpApi, Travelpayouts, LiteAPI, AeroDataBox, OneMap), rules RAG with cited sources, React page, `api-agent` comparison write-up |

Tests: 51 Kotlin tests, 7 Python tests, evals 30 / 30, p95 under 35 ms per mode on seed data.

Real-provider checks (6 Nov 2026, details in [docs/LIVE-CHECK.md](docs/LIVE-CHECK.md)):

| Item | Result |
|---|---|
| 1. Record real provider responses | Done for all five providers: real responses are test fixtures (`LiveRecordingTest`, keys redacted) |
| 2. Manual check of pain point 1 against Google Flights | Done: SIN→BKK 18 Dec, all 10 nonstop flights match Google Flights on flight, times and price |
| 3. LiteAPI capsule coverage and room size | Done: capsules/hostels are covered (8 near Marina Bay, 11 near True Digital Park) once requested by hotel type; room size and MRT walk are never provided (shown as unknown). Few list both a 24-hour desk and lockers, so unlisted now counts as unknown (see below); pain point 2 then plans OK on real data. Singapore sandbox prices look implausible and are unverified |
| 4. Speed on real providers | Done: FAST p50 0.9 s (max 3.8 s), LOW_COST p50 1.1 s; target under 10 s |
| 5. Generic API agent vs TripLah tools | Done on the Claude API (`api-agent` itself is OpenAI-only): domain tools 18 / 30 strict vs generic 11 / 30, fewer invented numbers (6 vs 10), cheaper. See [docs/COMPARISON.md](docs/COMPARISON.md) |

Decision (6 Nov 2026): in `LOW_COST_CONVENIENT`, a late check-in or lockers that the hotel data does not list counts as unknown. Such stays are shown with the unknown marked and ranked below stays known to meet every rule; a stay known to lack either is still rejected. This matches how FAMILY treats missing room size.

Findings from the live run: real SerpApi searches need more than 5 s on a cold start (real providers now get 15 s), and NTU → Changi takes 108 minutes by public transport (OneMap), so the default 60-minute campus-to-airport buffer is too short for NTU (the skill now passes 110).

## Sources

- [Travelpayouts Aviasales Data API](https://support.travelpayouts.com/hc/en-us/articles/203956163)
- [SerpApi free plan (Costbench)](https://costbench.com/software/web-scraping/serpapi/free-plan/)
- [FlightAware AeroAPI](https://www.flightaware.com/commercial/aeroapi/)
- [AeroDataBox (API Evangelist)](https://providers.apievangelist.com/providers/aerodatabox/)
- [LiteAPI FAQ](https://docs.liteapi.travel/docs/faq) and [LiteAPI MCP server](https://docs.liteapi.travel/reference/mcp-server)
- [Duffel seat maps](https://duffel.com/docs/api/seat-maps) and [Duffel excess search fee](https://help.duffel.com/hc/en-gb/articles/4412912264466-What-is-Excess-Search)
- [OneMap API overview](https://sheshbabu.com/posts/exploring-singapore-onemap-api)
- [Amadeus Self-Service shutdown](https://ignav.com/docs/amadeus-self-service-shutdown)
- [Flight cancellation and load factor study (SAGE)](https://journals.sagepub.com/doi/full/10.3141/2106-05)
- [Skift: United's algorithm for emptier flights](https://skift.com/?p=403510)

# TripLah

A trip planner for overseas students making short trips (1 day to 1 week) around a class timetable. It **plans only**: it never books, never pays and never stores card, passport or ID numbers. The output is a plan and booking links.

It solves three problems:

1. **One-day award ceremony or onsite hackathon abroad.** The cheapest or fastest flights that arrive before the event and land back before the next must-attend class.
2. **Hackathon with a partner.** Both timetables are intersected, a capsule or hostel bed is found within 15 minutes of the venue, 2 pods are compared with 1 private twin room, and the cost is split.
3. **Family visits Singapore, then everyone flies home together.** A family-friendly hotel, no stops, no red-eyes, a checked bag each, and the return leg searched as **one booking for everyone**.

See [PLAN.md](PLAN.md) for the full design and feasibility check.

## Quick start

Needs JDK 21 (Gradle comes with the wrapper). On this machine a portable JDK is in `~/.jdks`; in Git Bash run `. ./env.sh` first.

```bash
./gradlew test
```

```bash
./gradlew :evals:run
```

```bash
./gradlew :api:run
```

The API serves `http://127.0.0.1:8080` (`/v1/tools`, `/v1/plan`, `/openapi.json`). The web page:

```bash
npm --prefix web install && npm --prefix web run dev
```

Open `http://localhost:5173`, click **Use sample** for Traveller A, then **Plan trip**.

### Claude Code (MCP + skill)

```bash
./gradlew :mcp-server:fatJar
```

`.mcp.json` registers the `triplah` MCP server (stdio). Copy `skill/SKILL.md` to `.claude/skills/student-trip-planner/SKILL.md` and ask, for example: *"I have an award ceremony at the Queen Sirikit centre in Bangkok on 20 Nov, 2-5pm. Cheapest way there and back without missing my lab? My timetable is seed/timetables/traveller-a.ics."*

The MCP server can also forward to a running API: `java -jar mcp-server/build/libs/triplah-mcp.jar --api http://127.0.0.1:8080`.

**Other clients:** [docs/CONNECT.md](docs/CONNECT.md) has setup steps for Claude Desktop, Cursor, VS Code (Copilot), Cline, Continue, Zed, Gemini CLI, LM Studio, JetBrains, Windsurf, Codex CLI, Goose, and the remote apps ChatGPT and Claude.ai.

### Watcher

```bash
./gradlew :watcher:run --args="--every-minutes 60"
```

Without `--every-minutes` it runs once (for cron or Task Scheduler). Alerts go to stderr, the SQLite `alert` table, and Telegram if `TELEGRAM_BOT_TOKEN` and `TELEGRAM_CHAT_ID` are set.

### Rules service (stretch)

```bash
cd rules-rag && python -m venv .venv && .venv/Scripts/pip install -r requirements.txt && .venv/Scripts/uvicorn app:app --port 8008
```

## Layout

```
planner-core/  pure Kotlin, no I/O: timetable import (.ics, JSON), free windows, mode rules, planner,
               risk signal, buy-or-wait, cost split, recheck, grounding check, validation
providers/     FlightProvider / StayProvider / StatusProvider / TransitProvider: mocks on seed data,
               real adapters (SerpApi, Travelpayouts, LiteAPI, AeroDataBox, OneMap), ProviderHub
               (parallel calls, 5 s timeout, 12-min cache), quota guard, host allow-list, SQLite store
api/           TripService (shared by every entry point), tool registry, Ktor REST API, OpenAPI spec
mcp-server/    MCP over stdio (JSON-RPC 2.0): initialize, tools/list, tools/call
watcher/       price watch, flight recheck, alerts
evals/         30 prompts (10 per pain point), runner, report.md
rules-rag/     Python FastAPI: baggage and entry rules with cited sources (BM25, extractive)
web/           small React page
seed/          6 cities (SIN BKK KUL HKG TPE MNL): fictional flights, stays, history; sample timetables
skill/         SKILL.md "student-trip-planner"
docs/          comparison with Agoda's api-agent
```

## Providers

`TRIPLAH_PROVIDERS=mock` (default) uses seed data only; CI never calls a live API. With `TRIPLAH_PROVIDERS=real`, each real adapter whose key is set replaces its mock:

| Variable | Adapter |
|---|---|
| `SERPAPI_KEY` | Google Flights via SerpApi (live prices; checked-bag fee not included) |
| `TRAVELPAYOUTS_TOKEN` | Aviasales price calendar (cached prices) |
| `LITEAPI_KEY` | LiteAPI hotels and rates (sandbox key works) |
| `AERODATABOX_KEY` | Flight status via RapidAPI |
| `ONEMAP_TOKEN` | Singapore public-transport routing |

Secrets come from environment variables only. Outbound calls go only to allow-listed https hosts, with a 5-second timeout, and a quota guard stops each provider at 90% of its free tier (counts persist in SQLite).

## What is verified

| Check | Where | Result |
|---|---|---|
| Unit tests: SIN/BKK time zones, buffer edges, two-timetable intersection, must-attend never broken, every FAMILY rule | `planner-core` | 24 pass |
| Provider parsing from real recorded responses (all 5 providers) and edge-case fixtures, fare buckets, timeout, cache | `providers` | 13 pass |
| Pain points 1-3 end to end, recheck (2 h move then cancel), risk, price watch, calendar, guardrails (injected hotel text, invalid inputs, quota guard, allow-list), REST API | `api` | 14 pass |
| MCP protocol | `mcp-server` | 3 pass, plus a stdio smoke test of the jar |
| Watcher schedule and alerts | `watcher` | 2 pass |
| 30-prompt eval: zero must-attend conflicts, within budget, mode rules, grounded answer; speed | `evals/report.md` | 30 / 30, p95 under 35 ms per mode on seed data |
| Rules service | `rules-rag` | 7 pass |

## Live checks (6 Nov 2026)

- Real providers: all five work (SerpApi, Travelpayouts, LiteAPI, AeroDataBox, OneMap), and their real responses are test fixtures. SIN→BKK on 18 Dec matches Google Flights on all 10 nonstop flights. Plans take about 1 s on real providers. See [docs/LIVE-CHECK.md](docs/LIVE-CHECK.md).
- Generic API agent vs TripLah tools on the Claude API: TripLah's tools 18 / 30 strict vs 11 / 30. See [docs/COMPARISON.md](docs/COMPARISON.md).

## Not verified yet

- **LiteAPI prices in Singapore** look implausible on the sandbox key (a capsule at SGD 226 a night). Check with a production key before trusting them.

All flight numbers, times, hotels and prices in `seed/` are fictional test data.

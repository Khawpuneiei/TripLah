# Finishing the open items (needs your API keys)

**Status, 6 Nov 2026:** all five items are done (see [LIVE-CHECK.md](LIVE-CHECK.md) and [COMPARISON.md](COMPARISON.md)). Two follow-ups came out of them: the `LOW_COST_CONVENIENT` rule decision and the Singapore LiteAPI prices.

Everything else in PLAN.md is built and tested on seed data. These five checks need real accounts, which only you can create. Put keys in environment variables only: never in files, never in chat. The quota guard stops each provider at 90% of its free tier.

## 0. Sign up and set keys (Git Bash)

Confirm each free tier on the official pricing page at sign-up (PLAN.md, Data sources).

```bash
export TRIPLAH_PROVIDERS=real
export SERPAPI_KEY=...            # serpapi.com, free plan
export TRAVELPAYOUTS_TOKEN=...    # travelpayouts.com, Data API token
export LITEAPI_KEY=...            # liteapi.travel, sandbox key
export AERODATABOX_KEY=...        # RapidAPI, AeroDataBox free tier
export ONEMAP_TOKEN=...           # onemap.gov.sg, optional
. ./env.sh
```

## 1-4. Real responses, Google Flights check, LiteAPI check, real speed

One command does all four (it uses about 12 SerpApi searches):

```bash
TRIPLAH_RECORD_DIR=providers/src/test/resources/recorded/live ./gradlew :evals:liveCheck --args="2026-12-18 BKK th-qsncc"
```

It prints:

1. **Raw search** for SIN→BKK on that date. Open Google Flights for the same route and date and compare flight numbers, times and prices (expect small differences: Google's price excludes bags).
2. **Speed** of FAST and LOW_COST on real providers. Target: under 10 s.
3. **Stay data coverage**: how many LiteAPI results are capsules or hostels, and what share have room size, lift and MRT data (Open risk 2). If capsule coverage is poor, `LOW_COST_CONVENIENT` will return few plans on real data.
4. Raw responses saved in `providers/src/test/resources/recorded/live/` with keys redacted. Check them for anything personal, then copy the bodies over the hand-written fixtures and re-run `./gradlew :providers:test`.

Paste the printed output into PLAN.md under Status.

## 5. Generic API agent vs TripLah tools (Claude API)

`api-agent` itself only talks to OpenAI, so the comparison runs on the Claude API instead, with the same design in two arms:

- **generic**: Claude gets TripLah's OpenAPI spec and one `call_api` tool (what `api-agent` does);
- **domain**: Claude gets the typed TripLah tools and `skill/SKILL.md`.

Same 30 prompts, model `claude-opus-5-5`, effort `medium`, server-side refusal fallbacks on (`fallbacks: "default"`). Grading per answer: every price, time and flight number grounded in that run's tool results; for plans, the answer must contain a complete TripLah-validated plan (flights and dates); for expected no-plan cases, it must say no plan fits.

Needs Claude credentials: `ant auth login`, or `ANTHROPIC_API_KEY` exported in your shell. Then:

```bash
./gradlew :api:run
```

```bash
rules-rag/.venv/Scripts/python -I evals/claude_compare.py --cases p1-01,p2-01,p3-01
```

```bash
rules-rag/.venv/Scripts/python -I evals/claude_compare.py
```

The first is a pilot (under USD 1). The full run is 60 conversations, estimated USD 8-20; the report (`evals/claude_compare.md`) shows the measured tokens and cost per arm. `--dry-run` checks the harness without any Claude call (it passes 60/60 on TripLah's own answers, and rejects invented flights, invented prices, flights on the wrong day and plans for impossible trips).

**Tried on 6 Nov 2026 with `api-agent` + OpenAI:** it installed, read TripLah's spec and exposed one `127_0_0_1_query` tool, but OpenAI rejected the key in this environment (HTTP 401). `docs/api_agent_client.py` can still drive it with a valid OpenAI key.

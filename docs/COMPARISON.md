# TripLah vs Agoda's `api-agent`

Agoda open-sourced [`api-agent`](https://github.com/agoda-com/api-agent) in January 2026 ([press release](https://www.agoda.com/press/agoda-launches-open-source-api-agent-to-simplify-mcp-server-integrations/)). It is a universal MCP server. You point it at a GraphQL or REST service, it reads the schema, writes the queries from natural language, runs them, and can post-process results with SQL (DuckDB).

TripLah takes the opposite approach: a small set of hand-written, domain-specific MCP tools on top of a pure planning core.

The comparison below is based on the press release. I have **not** yet run `api-agent` against TripLah. That is the next step (see the end of this file).

| | `api-agent` (generic) | TripLah (domain tools) |
|---|---|---|
| Integration cost | Near zero per API: give it a schema | One Kotlin tool per capability |
| Who decides what is valid | The model, from the schema | Planner code: hard rules per mode, timetable check, budget |
| Hard constraints ("never break a must-attend class") | Has to be expressed in the prompt; the model may not follow it | Enforced in `planner-core`; a broken plan cannot be returned |
| Joining data | DuckDB SQL over results | Planner joins flights × stays × timetables in Kotlin |
| Output | Whatever the generated query returns | Typed JSON (`PlanResult`), with `fetchedAt` and `source` on every offer |
| Grounding check | Not described | Eval checks that every price, time and flight number in the answer appears in a tool result |
| Untrusted provider text | Passed through | Sanitised and flagged (`ProviderText`), tested with injected instructions |
| Quotas on free tiers | Not described | Per-provider quota guard, persisted in SQLite, stops at 90% |
| New API | Instant | Write an adapter + recorded-response test |

## Where each fits

- `api-agent` is the right tool for **exploring and reading** many internal APIs quickly: ad-hoc questions, dashboards, one-off data pulls.
- TripLah's style is right where a **wrong answer is costly** and the rules are known: a plan that misses an exam, or a family itinerary with a self-transfer. There, the rules belong in code and the model should only read the request and explain the result.

The two combine naturally. TripLah publishes an OpenAPI spec (`api/openapi.json`, served at `/openapi.json`), so `api-agent` could sit in front of the TripLah REST API for exploratory questions ("which routes did the watcher see get cheaper this month?"), while `plan_trip` stays a hand-written tool with hard rules.

## Measured (Claude API, 6 Nov 2026)

`api-agent` itself only supports OpenAI, so both designs were run on Claude with `evals/claude_compare.py`: a **generic** arm (TripLah's OpenAPI spec + one `call_api` tool, the `api-agent` approach) and a **domain** arm (typed TripLah tools + `skill/SKILL.md`). Same 30 prompts as `evals/cases.json`, seed data, `claude-opus-5-5`, effort `medium`. Full per-case table: [evals/claude_compare.md](../evals/claude_compare.md).

| | Generic (api-agent style) | Domain (TripLah) |
|---|---|---|
| Strict pass (every price, time and flight traceable; complete validated plan; impossible trips refused) | 11 / 30 | **18 / 30** |
| Lenient pass (also allows prices Claude calculated, e.g. a split per person) | 19 / 30 | **23 / 30** |
| Answers with an invented price, time or flight number | 10 | **6** |
| Plan / outcome failures | 2 | **1** |
| Tool calls per answer | 2.0 | **1.2** |
| Input tokens (30 prompts) | 383k | **295k** |
| Cost (30 prompts) | USD 2.69 | **USD 2.39** |

What the numbers say:

- Both arms called TripLah's `plan_trip`, so both got timetable-checked plans; the generic arm usually found it through the spec. The hard rules held either way, because they are in code.
- The difference is in the **answer**: with the generic arm, Claude more often restated numbers it had rounded or recomputed (e.g. "SGD 1,263" for 1,262.74, per-person splits not in the result). The skill's rule "quote prices exactly as returned" plus typed tools roughly halves that.
- The domain arm is cheaper: fewer calls and no OpenAPI spec in every request.
- Remaining domain failures are mostly derived values (a time Claude computed by adding a buffer, a per-person price). A next step is to return those values from the tools so Claude never has to compute them.

How the grader was checked: a dry run grades TripLah's own summaries 60 / 60, and invented flights, invented prices, flights on the wrong day, and plans for impossible trips are rejected. Two grader bugs found in the first run were fixed before these numbers (values from the user's own request counted as ungrounded; "couldn't find a plan" was not recognised as a no-plan answer), and three prompts that referred to an earlier case were rewritten to stand alone.

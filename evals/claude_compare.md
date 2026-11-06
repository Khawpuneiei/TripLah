# Generic API agent vs TripLah domain tools (Claude API)

Model `claude-opus-5-5`, effort `medium`, server-side refusal fallbacks on. Same 30 prompts as `evals/cases.json`, seed data. Generic arm = OpenAPI spec + one `call_api` tool (the api-agent approach). Domain arm = typed TripLah tools + `skill/SKILL.md`.

- **Strict**: every price, time and flight number in the answer appears in a tool result, the user's request, or the arguments Claude sent; plans are complete TripLah-validated plans (flights and dates); impossible trips are answered with no plan.
- **Lenient**: as strict, but allows prices Claude calculated from grounded numbers (a sum, difference, or a split between 2-9 people).
- **Invented**: answers with a price, time or flight number that is in no source and is not a simple calculation.

| Arm | Strict | Lenient | Invented | Rule / outcome failures | Tool calls (mean) | Input tokens | Output tokens | Est. cost (USD) |
|---|---|---|---|---|---|---|---|---|
| generic | 11 / 30 | 19 / 30 | 10 | 2 | 2.0 | 383,110 | 47,570 | 2.69 |
| domain | 18 / 30 | 23 / 30 | 6 | 1 | 1.2 | 294,826 | 56,253 | 2.39 |

| Case | Arm | Result | Calls |
|---|---|---|---|
| p1-01 | generic | pass | call_api /v1/tools/plan_trip, call_api /v1/tools/plan_trip |
| p1-01 | domain | pass | plan_trip |
| p1-02 | generic | pass (lenient) - derived price SGD 18.71 | call_api /v1/tools/plan_trip, call_api /v1/tools/plan_trip |
| p1-02 | domain | pass | plan_trip |
| p1-03 | generic | pass (lenient) - derived price SGD 13.42 | call_api /v1/tools/plan_trip |
| p1-03 | domain | pass (lenient) - derived price SGD 13.42 | plan_trip |
| p1-04 | generic | pass (lenient) - derived price SGD 324.48 | call_api /v1/tools/plan_trip |
| p1-04 | domain | FAIL: ungrounded time 21:50; derived price SGD 20.18 | plan_trip |
| p1-05 | generic | FAIL: ungrounded time 17:30 | call_api /v1/tools/plan_trip, call_api /v1/tools/plan_trip |
| p1-05 | domain | pass | plan_trip |
| p1-06 | generic | pass (lenient) - derived price SGD 13.58 | call_api /v1/tools/plan_trip |
| p1-06 | domain | pass (lenient) - derived price SGD 1.41 | plan_trip |
| p1-07 | generic | pass | call_api /v1/tools/plan_trip, call_api /v1/tools/find_free_windows |
| p1-07 | domain | FAIL: expected NO_PLAN: answer does not say no plan fits | plan_trip |
| p1-08 | generic | pass (lenient) - derived price SGD 64; derived price SGD 114 | call_api /v1/tools/plan_trip |
| p1-08 | domain | pass | plan_trip |
| p1-09 | generic | pass | call_api /v1/tools/plan_trip |
| p1-09 | domain | pass | plan_trip |
| p1-10 | generic | pass | call_api /v1/tools/plan_trip |
| p1-10 | domain | pass | plan_trip |
| p2-01 | generic | FAIL: ungrounded price SGD 405.42; ungrounded price SGD 202.71; ungrounded price SGD 431; derived price SGD 88.72; derived price SGD 88.40; derived price SGD 117.60; derived price SGD 44 | call_api /v1/tools/plan_trip, call_api /v1/tools/plan_trip, call_api /v1/tools/plan_trip, call_api /v1/tools/search_stay |
| p2-01 | domain | FAIL: ungrounded time 21:25 | plan_trip |
| p2-02 | generic | pass | call_api /v1/tools/plan_trip |
| p2-02 | domain | pass | plan_trip |
| p2-03 | generic | FAIL: ungrounded price SGD 500; derived price SGD 24.66 | call_api /v1/tools/plan_trip |
| p2-03 | domain | pass | plan_trip |
| p2-04 | generic | pass | call_api /v1/tools/plan_trip, call_api /v1/tools/find_free_windows, call_api /v1/tools/plan_trip, call_api /v1/tools/sea |
| p2-04 | domain | pass | plan_trip |
| p2-05 | generic | pass (lenient) - derived price SGD 7.56; derived price SGD 76 | call_api /v1/tools/plan_trip, call_api /v1/tools/plan_trip, call_api /v1/tools/search_stays, call_api /v1/tools/search_s |
| p2-05 | domain | pass (lenient) - derived price SGD 7.56 | plan_trip |
| p2-06 | generic | pass (lenient) - derived price SGD 8.40; derived price SGD 88 | call_api /v1/tools/plan_trip |
| p2-06 | domain | pass (lenient) - derived price SGD 88 | plan_trip |
| p2-07 | generic | FAIL: ungrounded time 13:35; ungrounded time 16:00; ungrounded time 09:10; ungrounded time 11:00; ungrounded time 13:20; ungrounded time 06:35; ungrounded time 14:45; expected NO_PLAN: answer does not say no plan fits | call_api /v1/tools/plan_trip, call_api /v1/tools/find_free_windows, call_api /v1/tools/search_flights |
| p2-07 | domain | FAIL: ungrounded time 11:00 | plan_trip, plan_trip, find_free_windows, find_free_windows |
| p2-08 | generic | FAIL: ungrounded price SGD 280; ungrounded price SGD 190 | call_api /v1/tools/plan_trip |
| p2-08 | domain | pass | plan_trip |
| p2-09 | generic | FAIL: ungrounded price SGD 51; ungrounded time 23:30; ungrounded time 23:00; derived price SGD 37 | call_api /v1/tools/plan_trip, call_api /v1/tools/plan_trip |
| p2-09 | domain | pass | plan_trip |
| p2-10 | generic | FAIL: ungrounded price SGD 254; ungrounded price SGD 127; ungrounded price SGD 318; derived price SGD 404.16 | call_api /v1/tools/price_calendar, call_api /v1/tools/search_flights, call_api /v1/tools/search_flights |
| p2-10 | domain | pass | price_calendar, search_flights, search_flights, plan_trip |
| p3-01 | generic | pass | call_api /v1/tools/plan_trip |
| p3-01 | domain | pass | plan_trip |
| p3-02 | generic | pass | call_api /v1/tools/plan_trip |
| p3-02 | domain | FAIL: ungrounded time 09:05 | plan_trip |
| p3-03 | generic | pass | call_api /v1/tools/plan_trip |
| p3-03 | domain | pass | plan_trip |
| p3-04 | generic | FAIL: ungrounded price SGD 1,263 | call_api /v1/tools/plan_trip |
| p3-04 | domain | FAIL: ungrounded time 09:05 | plan_trip |
| p3-05 | generic | pass | call_api /v1/tools/plan_trip |
| p3-05 | domain | pass | plan_trip |
| p3-06 | generic | FAIL: ungrounded price SGD 557; derived price SGD 58.84 | call_api /v1/tools/plan_trip |
| p3-06 | domain | FAIL: ungrounded time 02:45; derived price SGD 58.84 | plan_trip |
| p3-07 | generic | FAIL: ungrounded price SGD 1,134; ungrounded price SGD 228; ungrounded price SGD 1,030; ungrounded price SGD 1,950; ungrounded price SGD 677 | call_api /v1/tools/plan_trip, call_api /v1/tools/plan_trip |
| p3-07 | domain | pass | plan_trip |
| p3-08 | generic | pass (lenient) - derived price SGD 8 | call_api /v1/tools/plan_trip |
| p3-08 | domain | pass (lenient) - derived price SGD 8 | plan_trip |
| p3-09 | generic | FAIL: expected NO_PLAN: answer does not say no plan fits | call_api /v1/tools/plan_trip, call_api /v1/tools/find_free_windows, call_api /v1/tools/plan_trip |
| p3-09 | domain | pass | plan_trip |
| p3-10 | generic | pass | call_api /v1/tools/plan_trip, call_api /v1/tools/plan_trip |
| p3-10 | domain | pass | plan_trip |

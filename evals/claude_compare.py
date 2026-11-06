"""Compare two ways of giving Claude access to TripLah, on the 30 eval prompts.

  generic  - the api-agent approach: Claude gets TripLah's OpenAPI spec and one generic `call_api` tool.
  domain   - the TripLah approach: Claude gets the typed TripLah tools and skill/SKILL.md.

Both arms run on the Claude API (Anthropic Python SDK), same model, same prompts, same grading:
  grounded  - every price, time and flight number in the answer appears in a tool result of that run
  rules     - EVENT_TRIP/FAMILY_VISIT: the flights named are exactly the flights of a plan that TripLah's
              plan_trip returned (plan_trip only returns plans that keep must-attend classes, budget and mode rules);
              flights the model assembled itself are counted as unverified (fail)
  outcome   - expected NO_PLAN / NO_PLAN_IN_BUDGET: the answer says no plan fits and names no flights

Needs the TripLah API on 127.0.0.1:8080 (./gradlew :api:run) and Claude credentials
(ANTHROPIC_API_KEY, or `ant auth login`). Each full run makes paid API calls.

  python evals/claude_compare.py --dry-run          # plumbing only, no Claude calls
  python evals/claude_compare.py --cases p1-01,p3-01 # pilot
  python evals/claude_compare.py                     # all 30 x 2 arms
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import time
from pathlib import Path

import anthropic
import httpx2

ROOT = Path(__file__).resolve().parent.parent
API = "http://127.0.0.1:8080"
MODEL = "claude-opus-5-5"
EFFORT = "medium"  # Claude Opus 5.5's default, set explicitly
MAX_TURNS = 12
PRICE_IN, PRICE_OUT = 4.00, 20.00  # USD per 1M tokens, claude-opus-5-5

PRICE_RE = re.compile(r"(?:SGD|S\$|\$)\s?(\d{1,6}(?:,\d{3})*(?:\.\d{1,2})?)")
TIME_RE = re.compile(r"(?<!\d)([01]\d|2[0-3]):([0-5]\d)(?!\d)")
FLIGHT_RE = re.compile(r"\b([A-Z][A-Z0-9]|[A-Z0-9][A-Z])\s?(\d{1,4})\b")
NO_PLAN_RE = re.compile(r"no (workable |feasible |valid |single )?(plan|option|itinerary|round trip)|not (possible|feasible|workable)|"
                        r"(can(no|')t|could(n'?t| not)|won'?t|isn'?t able to|unable to) (find|make|fit|work|get you)|"
                        r"over (the |your )?budget|exceeds? (the |your )?budget|above (the |your )?budget|would (break|miss)", re.I)

http = httpx2.Client(base_url=API, timeout=60)


# ---------- TripLah access ----------

def triplah_tool(name: str, args: dict) -> tuple[str, bool]:
    r = http.post(f"/v1/tools/{name}", json=args)
    return r.text, r.status_code != 200


def call_api(method: str, path: str, body: dict | None) -> tuple[str, bool]:
    """The generic arm's only tool. Local TripLah API, /v1 and /openapi.json only."""
    if not (path.startswith("/v1/") or path == "/openapi.json") or ".." in path:
        return json.dumps({"error": "only /v1/... paths on the TripLah API are allowed"}), True
    r = http.request(method.upper(), path, json=body if method.upper() == "POST" else None)
    return r.text, r.status_code != 200


def setup() -> None:
    http.get("/healthz").raise_for_status()
    for t, p in (("A", "seed/timetables/traveller-a.ics"), ("B", "seed/timetables/traveller-b.json")):
        http.post("/v1/timetables", json={"traveller": t, "path": str(ROOT / p)}).raise_for_status()


# ---------- the two arms ----------

def domain_arm() -> tuple[str, list[dict], callable]:
    skill = (ROOT / "skill/SKILL.md").read_text(encoding="utf-8")
    skill = skill.split("---", 2)[2] if skill.startswith("---") else skill
    tools = [{"name": t["name"], "description": t["description"], "input_schema": t["inputSchema"]} for t in http.get("/v1/tools").json()]
    return skill, tools, lambda name, args: triplah_tool(name, args)


def generic_arm() -> tuple[str, list[dict], callable]:
    spec = http.get("/openapi.json").text
    system = ("You answer questions by calling a REST API. Use only the call_api tool. "
              "The OpenAPI specification of the API follows.\n\n" + spec)
    tools = [{
        "name": "call_api",
        "description": "Call the REST API described in the system prompt. Returns the response body.",
        "input_schema": {
            "type": "object",
            "properties": {
                "method": {"type": "string", "enum": ["GET", "POST"]},
                "path": {"type": "string", "description": "Path from the OpenAPI spec, e.g. /v1/tools/search_flights"},
                "body": {"type": "string", "description": "JSON request body for POST, as a JSON string, e.g. {\"from\": \"SIN\"}"},
            },
            "required": ["method", "path"],
            "additionalProperties": False,
        },
        "strict": True,
    }]
    def execute(name: str, args: dict) -> tuple[str, bool]:
        raw = args.get("body")
        try:
            body = json.loads(raw) if raw else None
        except json.JSONDecodeError as e:
            return json.dumps({"error": f"body is not valid JSON: {e}"}), True
        return call_api(args.get("method", "GET"), args.get("path", ""), body)

    return system, tools, execute


PREAMBLE = ("Context: I'm a student in Singapore (home airport SIN unless I say otherwise). Today is 10 Nov 2026. "
            "My timetable is already imported as Traveller A, and my girlfriend's as Traveller B. "
            "Answer with the plan: flight numbers, times and prices, or say clearly if no plan fits.\n\n")


def run_agent(client: anthropic.Anthropic, system: str, tools: list[dict], execute, prompt: str) -> dict:
    messages: list = [{"role": "user", "content": PREAMBLE + prompt}]
    results: list[str] = []
    usage = {"input": 0, "output": 0}
    calls: list[str] = []
    args_seen: list[str] = []
    final, stop = "", None
    for _ in range(MAX_TURNS):
        resp = client.beta.messages.create(
            model=MODEL,
            max_tokens=16000,
            system=system,
            tools=tools,
            messages=messages,
            output_config={"effort": EFFORT},
            cache_control={"type": "ephemeral"},  # system + tools + history repeat every turn
            betas=["server-side-fallback-2026-07-01"],
            fallbacks="default",
        )
        usage["input"] += resp.usage.input_tokens + (resp.usage.cache_creation_input_tokens or 0)
        usage["cached"] = usage.get("cached", 0) + (resp.usage.cache_read_input_tokens or 0)
        usage["output"] += resp.usage.output_tokens
        stop = resp.stop_reason
        # Append-only: echo the full content (thinking, fallback and tool_use blocks) unchanged.
        messages.append({"role": "assistant", "content": resp.content})
        if stop == "refusal":
            break
        uses = [b for b in resp.content if b.type == "tool_use"]
        final = "\n".join(b.text for b in resp.content if b.type == "text")
        if stop != "tool_use" or not uses:
            break
        out = []
        for u in uses:
            calls.append(u.name if u.name != "call_api" else f"call_api {u.input.get('path')}")
            args_seen.append(json.dumps(u.input))
            text, is_err = execute(u.name, u.input)
            results.append(text)
            out.append({"type": "tool_result", "tool_use_id": u.id, "content": text[:60000], "is_error": is_err})
        messages.append({"role": "user", "content": out})
    return {"answer": final, "results": results, "args": args_seen, "usage": usage, "calls": calls, "stop": stop}


# ---------- grading ----------

def walk(x, nums: set, strs: list):
    if isinstance(x, dict):
        for v in x.values():
            walk(v, nums, strs)
    elif isinstance(x, list):
        for v in x:
            walk(v, nums, strs)
    elif isinstance(x, bool):
        pass
    elif isinstance(x, (int, float)):
        nums.add(float(x))
    elif isinstance(x, str):
        strs.append(x)
        try:
            nums.add(float(x))
        except ValueError:
            pass


def answer_flights(answer: str) -> list[str]:
    return [m.group(1) + m.group(2) for m in FLIGHT_RE.finditer(answer) if any(c.isalpha() for c in m.group(1))]


def derived(v: float, nums: list[float]) -> bool:
    """v is a simple calculation from grounded numbers: a+b, a-b, a/k or a*k (k = 2..9)."""
    pool = [n for n in nums if 0.5 <= abs(n) <= 50000 and abs(n * 100 - round(n * 100)) < 1e-6]
    for i, a in enumerate(pool):
        for k in range(2, 10):
            if abs(a / k - v) < 0.011 or abs(a * k - v) < 0.011:
                return True
        for b in pool[i + 1:]:
            if abs(a + b - v) < 0.011 or abs(abs(a - b) - v) < 0.011:
                return True
    return False


def grounding(answer: str, results: list[str], request_sources: list[str]) -> tuple[list[str], list[str]]:
    """Returns (invented, derived). Sources: tool results, the user's request, and the arguments Claude sent to tools."""
    nums: set = set(); strs: list = []
    for r in results + request_sources:
        try:
            walk(json.loads(r), nums, strs)
        except json.JSONDecodeError:
            strs.append(r)
            nums.update(float(x.replace(",", "")) for x in re.findall(r"\d+(?:,\d{3})*(?:\.\d+)?", r))
    text = "\n".join(strs)
    times = {m.group(0) for m in TIME_RE.finditer(text)}
    flights = {m.group(1) + m.group(2) for s in strs for m in FLIGHT_RE.finditer(s)}
    invented, der = [], []
    numlist = sorted(nums)
    for m in PRICE_RE.finditer(answer):
        v = float(m.group(1).replace(",", ""))
        if any(abs(v - n) < 0.005 for n in nums):
            continue
        (der if derived(v, numlist) else invented).append(f"price {m.group(0)}")
    invented += [f"time {t}" for t in {m.group(0) for m in TIME_RE.finditer(answer)} if t not in times]
    invented += [f"flight {f}" for f in set(answer_flights(answer)) if f not in flights]
    return invented, der


def flight_fields(x, out: set) -> None:
    """Flight numbers from flightNo / flightNos fields only (not class titles such as SC2005)."""
    if isinstance(x, dict):
        for k, v in x.items():
            if k in ("flightNo", "flightNos") and isinstance(v, str):
                out.update(v.split("+"))
            else:
                flight_fields(v, out)
    elif isinstance(x, list):
        for v in x:
            flight_fields(v, out)


def named_flights(answer: str, results: list[str]) -> set[str]:
    known: set = set(); everything = "\n".join(results)
    for r in results:
        try:
            flight_fields(json.loads(r), known)
        except json.JSONDecodeError:
            pass
    cands = set(answer_flights(answer))
    # Known flights, plus codes that appear nowhere in the results (invented flights).
    return {f for f in cands if f in known or f not in everything}


def plans_from(results: list[str]) -> list[list[tuple[str, str]]]:
    """Each plan TripLah validated, as [(flight number, local departure date), ...]."""
    out = []
    for r in results:
        try:
            j = json.loads(r)
        except json.JSONDecodeError:
            continue
        if isinstance(j, dict) and "plans" in j and "status" in j:
            for p in j["plans"]:
                out.append([(f["flightNos"].split("+")[0], f["depart"][:10]) for f in p["flights"]])
    return out


MONTHS = ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"]
DAYS = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]


def states_date(window: str, iso: str) -> bool:
    import datetime as dt
    d = dt.date.fromisoformat(iso)
    w = window.lower()
    if iso in w:
        return True
    mon, day, wd = MONTHS[d.month - 1], d.day, DAYS[d.weekday()]
    return bool(re.search(rf"(?<!\d){day}(st|nd|rd|th)?\s+{mon}|{mon}[a-z]*\.?\s+{day}(?!\d)|\b{wd}", w))


def plan_in_answer(answer: str, plan: list[tuple[str, str]]) -> bool:
    """Every flight of the plan is named, with its date or weekday stated near it."""
    for no, iso in plan:
        hits = [m.start() for m in re.finditer(rf"\b{no[:2]}\s?{no[2:]}\b", answer)]
        if not any(states_date(answer[max(0, i - 120): i + 160], iso) for i in hits):
            return False
    return True


def grade(case: dict, run: dict, truth: dict) -> list[str]:
    f = []
    if run["stop"] == "refusal":
        return ["refused"]
    if not run["answer"].strip():
        return [f"no answer (stopped: {run['stop']})"]
    inv, der = grounding(run["answer"], run["results"], [PREAMBLE + case["prompt"]] + run.get("args", []))
    f += [f"ungrounded {b}" for b in inv] + [f"derived {b}" for b in der]
    expected = case["expect"]["status"]
    named = named_flights(run["answer"], run["results"] + [json.dumps(truth)])
    if case.get("tool", "plan_trip") == "price_calendar":
        return f
    if expected == "OK":
        valid = plans_from(run["results"]) + plans_from([json.dumps(truth)])
        valid_nos = set()
        for r in run["results"] + [json.dumps(truth)]:
            try:
                j = json.loads(r)
            except json.JSONDecodeError:
                continue
            if isinstance(j, dict) and "plans" in j:
                valid_nos |= {n for p in j["plans"] for fl in p["flights"] for n in fl["flightNos"].split("+")}
        if not named:
            f.append("no flights named")
        elif not any(plan_in_answer(run["answer"], p) for p in valid):
            f.append("no complete TripLah-validated plan (flights + dates) in the answer: timetable/budget/mode rules unverified")
        elif not {n for n in named} <= valid_nos:
            f.append("names flights outside every validated plan")
    else:
        if not NO_PLAN_RE.search(run["answer"]):
            f.append(f"expected {expected}: answer does not say no plan fits")
    return f


# ---------- main ----------

RUNS = ROOT / "evals/claude_compare_runs.jsonl"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true", help="no Claude calls: grade TripLah's own summary in both arms")
    ap.add_argument("--regrade", action="store_true", help="no Claude calls: re-grade the answers saved by the last run")
    ap.add_argument("--cases", help="comma-separated case ids")
    ap.add_argument("--merge", action="store_true", help="with --cases: replace only these cases in the saved runs, then report on all")
    ap.add_argument("--out", default=str(ROOT / "evals/claude_compare.md"))
    args = ap.parse_args()

    setup()
    cases = json.loads((ROOT / "evals/cases.json").read_text(encoding="utf-8"))["cases"]
    if args.cases:
        keep = set(args.cases.split(","))
        cases = [c for c in cases if c["id"] in keep]
    by_id = {c["id"]: c for c in cases}
    rows = []

    if args.regrade:
        for line in RUNS.read_text(encoding="utf-8").splitlines():
            r = json.loads(line)
            c = by_id.get(r["id"])
            if not c:
                continue
            truth = json.loads(triplah_tool(c.get("tool", "plan_trip"), c["request"])[0])
            r["fails"] = grade(c, r, truth)
            rows.append(r)
        write_report(rows, Path(args.out), False)
        return 0

    arms = {"generic": generic_arm(), "domain": domain_arm()}
    client = None if args.dry_run else anthropic.Anthropic()
    kept = []
    if args.merge and RUNS.exists():
        kept = [l for l in RUNS.read_text(encoding="utf-8").splitlines() if json.loads(l)["id"] not in by_id]
    saved = None if args.dry_run else RUNS.open("w", encoding="utf-8")
    for l in kept:
        saved.write(l + "\n")
    for c in cases:
        truth = json.loads(triplah_tool(c.get("tool", "plan_trip"), c["request"])[0])
        for arm, (system, tools, execute) in arms.items():
            t0 = time.time()
            if args.dry_run:
                run = {"answer": truth.get("summary", "No plan fits." if truth.get("status") != "OK" else ""),
                       "results": [json.dumps(truth)], "args": [], "usage": {"input": 0, "output": 0}, "calls": ["(dry run)"], "stop": "end_turn"}
                if c.get("tool") == "price_calendar":
                    run["answer"] = "See the calendar options."
            else:
                try:
                    run = run_agent(client, system, tools, execute, c["prompt"])
                except anthropic.APIStatusError as e:
                    run = {"answer": "", "results": [], "args": [], "usage": {"input": 0, "output": 0}, "calls": [], "stop": f"api error {e.status_code}: {str(e.message)[:200]}"}
            fails = grade(c, run, truth)
            row = {"id": c["id"], "pp": c["painPoint"], "arm": arm, "secs": time.time() - t0, **run, "fails": fails}
            rows.append(row)
            if saved:
                saved.write(json.dumps(row, ensure_ascii=False) + "\n"); saved.flush()
            print(f"{c['id']:6} {arm:8} {'pass' if not fails else 'FAIL'}  {'; '.join(fails)[:150]}", flush=True)
    if saved:
        saved.close()
    if args.merge:
        all_rows = [json.loads(l) for l in RUNS.read_text(encoding="utf-8").splitlines()]
        order = {c["id"]: i for i, c in enumerate(json.loads((ROOT / "evals/cases.json").read_text(encoding="utf-8"))["cases"])}
        rows = sorted(all_rows, key=lambda r: (order.get(r["id"], 99), r["arm"] != "generic"))
    write_report(rows, Path(args.out), args.dry_run)
    return 0


def write_report(rows: list[dict], out: Path, dry: bool) -> None:
    hard = lambda r: [f for f in r["fails"] if not f.startswith("derived")]
    lines = ["# Generic API agent vs TripLah domain tools (Claude API)", "",
             f"Model `{MODEL}`, effort `{EFFORT}`, server-side refusal fallbacks on. Same 30 prompts as `evals/cases.json`, seed data. "
             "Generic arm = OpenAPI spec + one `call_api` tool (the api-agent approach). Domain arm = typed TripLah tools + `skill/SKILL.md`.",
             "",
             "- **Strict**: every price, time and flight number in the answer appears in a tool result, the user's request, or the arguments Claude sent; "
             "plans are complete TripLah-validated plans (flights and dates); impossible trips are answered with no plan.",
             "- **Lenient**: as strict, but allows prices Claude calculated from grounded numbers (a sum, difference, or a split between 2-9 people).",
             "- **Invented**: answers with a price, time or flight number that is in no source and is not a simple calculation.",
             ""]
    if dry:
        lines += ["**Dry run: no Claude calls. Both arms graded TripLah's own summary; this checks the harness only.**", ""]
    lines += ["| Arm | Strict | Lenient | Invented | Rule / outcome failures | Tool calls (mean) | Input tokens | Output tokens | Est. cost (USD) |",
              "|---|---|---|---|---|---|---|---|---|"]
    for arm in ("generic", "domain"):
        rs = [r for r in rows if r["arm"] == arm]
        if not rs:
            continue
        ti = sum(r["usage"]["input"] for r in rs); to = sum(r["usage"]["output"] for r in rs)
        cost = (ti * PRICE_IN + sum(r["usage"].get("cached", 0) for r in rs) * PRICE_IN * 0.1 + to * PRICE_OUT) / 1e6
        lines.append(f"| {arm} | {sum(not r['fails'] for r in rs)} / {len(rs)} | {sum(not hard(r) for r in rs)} / {len(rs)} | "
                     f"{sum(any(f.startswith('ungrounded') for f in r['fails']) for r in rs)} | "
                     f"{sum(any(not f.startswith(('ungrounded', 'derived')) for f in r['fails']) for r in rs)} | "
                     f"{sum(len(r['calls']) for r in rs) / len(rs):.1f} | {ti:,} | {to:,} | {cost:.2f} |")
    lines += ["", "| Case | Arm | Result | Calls |", "|---|---|---|---|"]
    for r in rows:
        res = "pass" if not r["fails"] else ("pass (lenient) - " if not hard(r) else "FAIL: ") + "; ".join(r["fails"]).replace("|", "/")
        lines.append(f"| {r['id']} | {r['arm']} | {res} | {', '.join(r['calls'])[:120]} |")
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"\nwrote {out}")


if __name__ == "__main__":
    sys.exit(main())

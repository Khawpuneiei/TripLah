# Live provider check, 6 Nov 2026

`./gradlew :evals:liveCheck --args="2026-12-18 BKK th-qsncc"` with real keys (`TRIPLAH_PROVIDERS=real`). Trip: SIN → BKK, Fri 18 Dec 2026, event 14:00-17:00 at the Queen Sirikit centre, Traveller A's timetable.

## Every provider once

| Provider | Result |
|---|---|
| SerpApi (Google Flights) | Works. 10 nonstop offers (Scoot TR, Thai TG). The very first search timed out at the 5 s limit; real providers now get 15 s (seed-data mocks keep 5 s). |
| Travelpayouts (calendar) | Works. 31 December dates; cheapest SIN→BKK 28 Dec, SGD 119 per seat (cached price). |
| AeroDataBox (status) | Works. TR610 on 18 Dec: SCHEDULED, 16:00, same time as SerpApi. |
| OneMap (transit) | Works. NTU → Changi by public transport: **108 min**. |
| LiteAPI (hotels) | Works with the sandbox key (`sand_...`). The first key tried returned HTTP 401 because it was not the API key. |

## 1. Google Flights comparison (manual check)

TripLah's raw search vs Google Flights for the same route and date, checked in a browser on 6 Nov 2026:

| TripLah (SerpApi) | Google Flights |
|---|---|
| TR610 16:00→17:35 SGD 326 | 4:00 PM→5:35 PM SGD 326 |
| TR688 14:25→16:00 SGD 407 | 2:25 PM→4:00 PM SGD 407 |
| TR636 17:40→19:15 SGD 407 | 5:40 PM→7:15 PM SGD 407 |
| TR620 19:40→21:15 SGD 407 | 7:40 PM→9:15 PM SGD 407 |
| TR608 06:40→08:15 SGD 472 | 6:40 AM→8:15 AM SGD 472 |
| TR624 09:15→10:50 SGD 472 | 9:15 AM→10:50 AM SGD 472 |
| TG414 15:55→17:15 SGD 546 | 3:55 PM→5:15 PM SGD 546 |
| TG402 08:15→09:35 SGD 546 | 8:15 AM→9:35 AM SGD 546 |
| TG404 12:25→13:45 SGD 546 | 12:25 PM→1:45 PM SGD 546 |
| TG408 19:15→20:35 SGD 604 | 7:15 PM→8:35 PM SGD 604 |

All 10 match on flight, times and price. Google also lists 1-stop options, which this search excluded (nonstop only). Neither price includes checked bags.

## 2. Speed on real providers (target under 10 s)

Caches cleared before each of 3 runs:

| Mode | p50 | max |
|---|---|---|
| FAST | 0.9 s | 3.8 s |
| LOW_COST | 1.1 s | 1.1 s |

Both are well under 10 s. Plans came back OK (FAST best: TR608 out, SQ713 back, SGD 805 total, mid-December peak prices).

## 3. LiteAPI capsule coverage and room size (Open risk 2)

Within 3 km of the venue, one night, 1 guest (sandbox key):

| | Singapore, Marina Bay expo (18 Dec) | Bangkok, True Digital Park (21 Nov) |
|---|---|---|
| Stays with rates | 46 | 45 |
| Capsules / hostels | 8 | 11 |
| ... with shared beds or pods | 5 | 4 |
| ... listing a 24-hour front desk (late check-in) | 1 | 7 |
| ... listing lockers | 5 | 4 |
| **... meeting every `LOW_COST_CONVENIENT` rule** | **1** | **0** |
| Lift known | 86% | 60% |
| Room size known | 0% | 0% |
| Walk to MRT known | 0% | 0% |

What this means:

- **Capsule and hostel coverage is real** (Cube Boutique Capsule Hotel, Jyu Capsule Hotel, Hostel 24 Backpackers, ...), but only when asked for. The adapter first searched the 40 nearest properties, which are all ordinary hotels. It now runs a second search for LiteAPI hotel types 225 (capsule hotels), 203 and 264 (hostels), and reads the type instead of guessing from the name.
- **Late check-in, lockers and lift** come from LiteAPI's facility list (8 = 24-hour front desk, 142 = lockers, 48/491 = lift, 972 = no lift). A facility that is not listed is stored as unknown, not as "no".
- **Room size and walk to MRT are never provided.** FAMILY mode shows them as unknown, as planned.
- **`LOW_COST_CONVENIENT` was too strict for real data**: it required a *listed* 24-hour front desk and *listed* lockers, and few hostels list both. **Decision (6 Nov): unlisted counts as unknown.** A stay known to lack late check-in or lockers is still rejected; an unlisted one is kept, marked "unknown" in the plan, and ranked below every stay known to meet the rules.
- **Pain point 2 on real data, after the change** (`liveCheck --convenient`, Travellers A + B, hackathon at True Digital Park 21-22 Nov): status OK. TG410 out Fri 20 Nov 21:00, Scoot back Sun 22 Nov (TR611 / TR637 / TR621 as the three options), Sunshiny-BB Hostel 2 minutes from the venue with a listed 24-hour desk (no unknowns), SGD 1,942.76 for both. About 12 s including Gradle start-up.
- **LiteAPI rate limits bursts** (HTTP 429 on the first run): TripLah now sends LiteAPI one search at a time, caches the hotel lists per place (they don't depend on dates), and the gateway retries a 429 after the requested wait (at most 5 s, twice).
- **Singapore sandbox prices look wrong.** A single capsule at SGD 226 a night and a dorm bed at SGD 322 are far above market; Bangkok's SGD 6-20 looks realistic. Not yet explained (sandbox test rates, or a currency issue); do not trust LiteAPI Singapore prices until checked with a production key.

Room names are classified with care: "Family Room with Bunk Bed" is a private family room, "1 Bed in 12 Beds Mixed Room" is a dorm bed, "Budget Double Room" in a hostel is private. Real names from this run are a unit test.

## 4. Recorded responses

One real response per provider (LiteAPI: two hotel lists and the rates) is now a test fixture (`providers/src/test/resources/recorded/*_live.json`, keys redacted, request metadata removed), tested by `LiveRecordingTest`. The hand-written fixtures stay for edge cases the live data didn't contain (one-stop, empty itinerary). Raw recordings stay local (`recorded/live/`, git-ignored).

## Finding: the campus-to-airport buffer is too short for NTU

The default `campusToAirportMin` is 60. OneMap says NTU → Changi takes 108 minutes by public transport, so a plan with the default could have Traveller A leave campus too late. Until TripLah computes this from OneMap automatically, pass `"buffers": {"campusToAirportMin": 110}` for NTU (the skill now says so).

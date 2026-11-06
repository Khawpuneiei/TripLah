# Recorded provider responses

Provider tests parse these files. CI makes no live calls.

These fixtures were **hand-written from each provider's documented response shape**, not captured from a live
account (no API keys existed when they were written). Once a key is available, replace each file with a real
recorded response (strip keys and personal data) and keep the tests passing.

| File | Provider | Endpoint |
|---|---|---|
| serpapi_google_flights.json | SerpApi | `GET /search.json?engine=google_flights` |
| travelpayouts_prices_for_dates.json | Travelpayouts | `GET /aviasales/v3/prices_for_dates` |
| liteapi_hotels.json, liteapi_rates.json | LiteAPI v3 | `GET /data/hotels`, `POST /hotels/rates` |
| aerodatabox_flight.json | AeroDataBox | `GET /flights/number/{number}/{date}` |
| onemap_route.json | OneMap | `GET /api/public/routingsvc/route` |

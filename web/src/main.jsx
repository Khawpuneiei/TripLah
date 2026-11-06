import React, { useEffect, useState } from 'react'
import { createRoot } from 'react-dom/client'
import './style.css'

async function call(path, body) {
  const r = await fetch(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
  const j = await r.json()
  if (!r.ok) throw new Error(j.error || `HTTP ${r.status}`)
  return j
}

const fmtTime = (iso) => iso.slice(0, 16).replace('T', ' ')
const sgd = (x) => `SGD ${x.toFixed(2)}`

function Timetables({ onChange }) {
  const [loaded, setLoaded] = useState({})
  const [err, setErr] = useState(null)
  const load = async (traveller, body) => {
    setErr(null)
    try {
      const r = await call('/v1/timetables', { traveller, ...body })
      const next = { ...loaded, [traveller]: r }
      setLoaded(next); onChange(Object.keys(next))
    } catch (e) { setErr(e.message) }
  }
  const upload = (traveller) => async (e) => {
    const f = e.target.files[0]; if (!f) return
    load(traveller, { content: await f.text(), format: f.name.endsWith('.json') ? 'json' : 'ics' })
  }
  return (
    <section>
      <h2>1. Timetables</h2>
      <p className="muted">Stays on this machine. Travellers are "A" and "B": no names or ID numbers.</p>
      {['A', 'B'].map((t) => (
        <div key={t} className="row">
          <strong>Traveller {t}</strong>
          <input type="file" accept=".ics,.json" onChange={upload(t)} aria-label={`Timetable for traveller ${t}`} />
          <button onClick={() => load(t, { path: t === 'A' ? 'seed/timetables/traveller-a.ics' : 'seed/timetables/traveller-b.json' })}>Use sample</button>
          {loaded[t] && <span className="ok">{loaded[t].events} events: {loaded[t].mustAttend} must-attend, {loaded[t].canSkip} can-skip</span>}
        </div>
      ))}
      {err && <p className="error">{err}</p>}
    </section>
  )
}

function PlanForm({ travellers, onResult }) {
  const [venues, setVenues] = useState([])
  const [f, setF] = useState({ mode: 'LOW_COST', origin: 'SIN', destination: 'BKK', venueId: 'th-qsncc', name: 'Award ceremony',
    start: '2026-11-20T14:00+07:00', end: '2026-11-20T17:00+07:00', budget: '', covers: false, pax: '' })
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState(null)
  useEffect(() => { fetch('/v1/venues').then((r) => r.json()).then(setVenues).catch(() => {}) }, [])
  const set = (k) => (e) => setF({ ...f, [k]: e.target.type === 'checkbox' ? e.target.checked : e.target.value })
  const submit = async (e) => {
    e.preventDefault(); setBusy(true); setErr(null)
    try {
      const body = { mode: f.mode, origin: f.origin, destination: f.destination, travellers,
        event: { name: f.name, start: f.start, end: f.end, venueId: f.venueId || undefined }, organiserCoversStay: f.covers }
      if (f.budget) body.budget = Number(f.budget)
      if (f.pax) body.passengers = Number(f.pax)
      onResult(await call('/v1/plan', body))
    } catch (e2) { setErr(e2.message) } finally { setBusy(false) }
  }
  const dest = venues.filter((v) => v.airport === f.destination)
  return (
    <section>
      <h2>2. Trip</h2>
      <form onSubmit={submit} className="grid">
        <label>Mode<select value={f.mode} onChange={set('mode')}>
          <option value="FAST">FAST (trip within 72 h)</option><option value="LOW_COST">LOW_COST</option>
          <option value="LOW_COST_CONVENIENT">LOW_COST_CONVENIENT (capsule near venue)</option><option value="FAMILY">FAMILY</option></select></label>
        <label>From<select value={f.origin} onChange={set('origin')}>{['SIN', 'BKK', 'KUL', 'HKG', 'TPE', 'MNL'].map((a) => <option key={a}>{a}</option>)}</select></label>
        <label>To<select value={f.destination} onChange={(e) => setF({ ...f, destination: e.target.value, venueId: '' })}>{['SIN', 'BKK', 'KUL', 'HKG', 'TPE', 'MNL'].map((a) => <option key={a}>{a}</option>)}</select></label>
        <label>Venue<select value={f.venueId} onChange={set('venueId')}><option value="">(city centre)</option>{dest.map((v) => <option key={v.id} value={v.id}>{v.name}</option>)}</select></label>
        <label>Event<input value={f.name} onChange={set('name')} /></label>
        <label>Starts (with offset)<input value={f.start} onChange={set('start')} /></label>
        <label>Ends (with offset)<input value={f.end} onChange={set('end')} /></label>
        <label>Budget, SGD total<input value={f.budget} onChange={set('budget')} inputMode="decimal" /></label>
        <label>Seats (default: travellers)<input value={f.pax} onChange={set('pax')} inputMode="numeric" /></label>
        <label className="check"><input type="checkbox" checked={f.covers} onChange={set('covers')} /> Organiser covers the stay</label>
        <button type="submit" disabled={busy}>{busy ? 'Planning…' : 'Plan trip'}</button>
      </form>
      <p className="muted">Checking timetables of: {travellers.length ? travellers.map((t) => `Traveller ${t}`).join(', ') : 'nobody yet'}</p>
      {err && <p className="error">{err}</p>}
    </section>
  )
}

function Result({ r }) {
  if (!r) return null
  return (
    <section>
      <h2>3. Plans <span className={`badge ${r.status}`}>{r.status}</span></h2>
      {r.notes.map((n, i) => <p key={i} className="note">{n}</p>)}
      {r.plans.map((p) => (
        <article key={p.rank} className="plan">
          <h3>{p.label} · {sgd(p.costs.total)} <span className="muted">({sgd(p.costs.perPerson)} each)</span></h3>
          <table><tbody>
            {p.flights.map((fl) => (
              <tr key={fl.offerId}><td>{fl.leg}</td><td><strong>{fl.flightNos}</strong></td><td>{fl.from} {fmtTime(fl.depart)}</td><td>→ {fl.to} {fmtTime(fl.arrive)}</td>
                <td>{fl.stops ? `${fl.stops} stop` : 'direct'}{fl.redEye ? ', red-eye' : ''}</td><td>{sgd(fl.price)}</td><td><a href={fl.bookingLink} target="_blank" rel="noreferrer">link</a></td></tr>
            ))}
            {p.stay && <tr><td>stay</td><td colSpan="3"><strong>{p.stay.name}</strong>: {p.stay.roomCount} × {p.stay.roomName}, {p.stay.nights} night(s)</td>
              <td>{p.stay.minutesToVenue != null ? `${p.stay.minutesToVenue} min to venue` : ''}</td><td>{sgd(p.stay.total)}</td><td><a href={p.stay.bookingLink} target="_blank" rel="noreferrer">link</a></td></tr>}
          </tbody></table>
          {p.skippedClasses.length > 0 && <p>Misses can-skip: {p.skippedClasses.map((s) => s.title).join(', ')}</p>}
          {p.warnings.map((w, i) => <p key={i} className="warn">{w}</p>)}
        </article>
      ))}
      {r.stayComparison.length > 0 && (
        <p>Stay comparison: {r.stayComparison.map((c) => `${c.option} at ${c.stayName}: ${sgd(c.total)} (${sgd(c.perPerson)} each)`).join(' vs ')}</p>
      )}
      {Object.keys(r.rejected).length > 0 && (
        <details><summary>Why other options were dropped</summary><ul>{Object.entries(r.rejected).map(([k, v]) => <li key={k}>{k}: {v}</li>)}</ul></details>
      )}
      <p className="muted">TripLah plans only. It never books or pays. Prices were fetched at the times shown by the provider and must be re-checked before booking.</p>
    </section>
  )
}

function App() {
  const [travellers, setTravellers] = useState([])
  const [result, setResult] = useState(null)
  return (
    <main>
      <h1>TripLah</h1>
      <p className="muted">Short trips that fit your class timetable.</p>
      <Timetables onChange={setTravellers} />
      <PlanForm travellers={travellers} onResult={setResult} />
      <Result r={result} />
    </main>
  )
}

createRoot(document.getElementById('root')).render(<App />)

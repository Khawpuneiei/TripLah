"""TripLah travel rules service (stretch): baggage, refund and entry rules with a cited source.

Retrieval-only RAG: BM25 over a small, hand-checked corpus. The answer is the retrieved passage
itself plus its source, so nothing is generated that the source does not say. Every answer tells
the user to check the official site, because entry rules go out of date.

Run:  uvicorn app:app --port 8008
"""
from __future__ import annotations

import json
import math
import re
from collections import Counter
from datetime import date
from pathlib import Path
from typing import Optional

from fastapi import FastAPI
from pydantic import BaseModel, Field

CORPUS_PATH = Path(__file__).with_name("corpus.json")
STALE_AFTER_DAYS = 180
MIN_SCORE = 1.0
STOP = set("a an the and or of to in on for is are be do does i my me we our you your can with at by it this that what how when need".split())


def tokens(s: str) -> list[str]:
    return [t for t in re.findall(r"[a-z0-9]+", s.lower()) if t not in STOP]


class Bm25:
    def __init__(self, docs: list[dict], k1: float = 1.4, b: float = 0.75):
        self.docs = docs
        self.k1, self.b = k1, b
        self.tf = [Counter(tokens(d["title"] + " " + d["text"])) for d in docs]
        self.len = [sum(t.values()) for t in self.tf]
        self.avg = sum(self.len) / max(1, len(self.len))
        df = Counter(w for t in self.tf for w in t)
        n = len(docs)
        self.idf = {w: math.log(1 + (n - c + 0.5) / (c + 0.5)) for w, c in df.items()}

    def search(self, q: str, k: int = 3) -> list[tuple[float, dict]]:
        qt = tokens(q)
        scored = []
        for i, d in enumerate(self.docs):
            s = 0.0
            for w in qt:
                f = self.tf[i].get(w, 0)
                if f:
                    s += self.idf[w] * f * (self.k1 + 1) / (f + self.k1 * (1 - self.b + self.b * self.len[i] / self.avg))
            scored.append((s, d))
        scored.sort(key=lambda x: -x[0])
        return [x for x in scored[:k] if x[0] > 0]


class Ask(BaseModel):
    question: str = Field(min_length=3, max_length=500)
    airline: Optional[str] = Field(default=None, max_length=3)
    country: Optional[str] = Field(default=None, max_length=2)


class Citation(BaseModel):
    id: str
    title: str
    source: str
    url: str
    checked: str
    stale: bool


class Answer(BaseModel):
    found: bool
    answer: str
    citations: list[Citation]
    disclaimer: str = "Rules change. Always check the official site linked above before you travel."


def load_corpus(path: Path = CORPUS_PATH) -> list[dict]:
    docs = json.loads(path.read_text(encoding="utf-8"))
    for d in docs:
        for key in ("id", "title", "text", "source", "url", "checked"):
            if not d.get(key):
                raise ValueError(f"corpus entry {d.get('id')} is missing {key}")
    return docs


DOCS = load_corpus()
INDEX = Bm25(DOCS)
app = FastAPI(title="TripLah travel rules", version="0.1.0")


def answer(q: Ask, today: date | None = None) -> Answer:
    today = today or date.today()
    query = q.question
    hits = INDEX.search(query + " " + (q.airline or "") + " " + (q.country or ""), k=5)
    # Filters narrow the hits; a document without the field is general guidance and is kept.
    if q.country:
        hits = [h for h in hits if h[1].get("country") in (None, q.country.upper())]
    if q.airline:
        hits = [h for h in hits if h[1].get("airline") in (None, q.airline.upper())]
    hits = [h for h in hits if h[0] >= MIN_SCORE][:2]
    if not hits:
        return Answer(found=False, answer="I have no checked source for that. Check the airline's or the immigration authority's official site.", citations=[])
    cites = [
        Citation(id=d["id"], title=d["title"], source=d["source"], url=d["url"], checked=d["checked"],
                 stale=(today - date.fromisoformat(d["checked"])).days > STALE_AFTER_DAYS)
        for _, d in hits
    ]
    body = "\n\n".join(f"{d['title']}: {d['text']} [{i + 1}]" for i, (_, d) in enumerate(hits))
    if any(c.stale for c in cites):
        body += "\n\nNote: at least one source was last checked over 6 months ago."
    return Answer(found=True, answer=body, citations=cites)


@app.get("/healthz")
def healthz() -> dict:
    return {"ok": True, "documents": len(DOCS)}


@app.post("/ask", response_model=Answer)
def ask(q: Ask) -> Answer:
    return answer(q)

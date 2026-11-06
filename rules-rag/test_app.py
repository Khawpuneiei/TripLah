from datetime import date

from fastapi.testclient import TestClient

import app as rules

client = TestClient(rules.app)


def test_entry_card_question_cites_official_source():
    r = client.post("/ask", json={"question": "Do I need an arrival card for Thailand and when do I submit it?"})
    assert r.status_code == 200
    a = r.json()
    assert a["found"]
    assert a["citations"][0]["id"] == "th-tdac"
    assert "3 days" in a["answer"]
    assert a["citations"][0]["url"].startswith("https://")
    assert "official site" in a["disclaimer"]


def test_country_filter_and_singaporean_exemption():
    a = client.post("/ask", json={"question": "arrival card exempt Singapore citizens", "country": "MY"}).json()
    assert a["citations"][0]["id"] == "my-mdac"


def test_baggage_question():
    a = client.post("/ask", json={"question": "how many kg cabin baggage", "airline": "AK"}).json()
    assert a["citations"][0]["id"] == "ak-cabin-bag"
    assert "7 kg" in a["answer"]


def test_unknown_question_does_not_make_things_up():
    a = client.post("/ask", json={"question": "visa rules for Antarctica penguins"}).json()
    assert not a["found"]
    assert a["citations"] == []


def test_stale_sources_are_flagged():
    a = rules.answer(rules.Ask(question="SG Arrival Card"), today=date(2027, 12, 1))
    assert a.citations[0].stale
    assert "6 months" in a.answer


def test_input_validation():
    assert client.post("/ask", json={"question": "x" * 600}).status_code == 422
    assert client.post("/ask", json={}).status_code == 422


def test_every_corpus_entry_has_a_source():
    for d in rules.load_corpus():
        assert d["url"].startswith("https://") and d["checked"]

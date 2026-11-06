"""Tiny streamable-HTTP MCP client for api-agent. Usage: python mcp_client.py list | call <tool> <json-args>"""
import json
import sys

import httpx

URL = "http://localhost:3000/mcp"
HEADERS = {
    "Accept": "application/json, text/event-stream",
    "Content-Type": "application/json",
    "X-Target-URL": "http://127.0.0.1:8080/openapi.json",
    "X-API-Type": "rest",
    "X-Allow-Unsafe-Paths": json.dumps(["/v1/tools/*"]),
}


def parse(r):
    if r.headers.get("content-type", "").startswith("text/event-stream"):
        msgs = [json.loads(l[5:]) for l in r.text.splitlines() if l.startswith("data:")]
        return msgs[-1] if msgs else None
    return r.json() if r.text else None


class Client:
    def __init__(self):
        self.c = httpx.Client(timeout=600)
        self.h = dict(HEADERS)
        r = self.c.post(URL, headers=self.h, json={"jsonrpc": "2.0", "id": 1, "method": "initialize",
                       "params": {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "triplah-eval", "version": "1"}}})
        r.raise_for_status()
        sid = r.headers.get("mcp-session-id")
        if sid:
            self.h["mcp-session-id"] = sid
        self.c.post(URL, headers=self.h, json={"jsonrpc": "2.0", "method": "notifications/initialized"})
        self.n = 1

    def rpc(self, method, params):
        self.n += 1
        r = self.c.post(URL, headers=self.h, json={"jsonrpc": "2.0", "id": self.n, "method": method, "params": params})
        r.raise_for_status()
        return parse(r)


if __name__ == "__main__":
    c = Client()
    if sys.argv[1] == "list":
        print(json.dumps(c.rpc("tools/list", {}), indent=1)[:4000])
    else:
        print(json.dumps(c.rpc("tools/call", {"name": sys.argv[2], "arguments": json.loads(sys.argv[3])}), indent=1)[:6000])

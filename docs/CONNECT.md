# Connect TripLah to your AI agent or chat app

TripLah ships an MCP server (`mcp-server/build/libs/triplah-mcp.jar`) that speaks MCP over **stdio**. Any client that can launch a local MCP server can use it. Web chat apps (ChatGPT, Claude.ai) only connect to **remote** servers, so they need a bridge; see [Remote clients](#remote-clients-chatgpt-claudeai).

Formats below were checked against each client's own docs on 6 Nov 2026, except where marked **unverified**. Client config formats change often: if one doesn't work, check the linked page.

## 1. Build the server

Needs JDK 21.

```bash
./gradlew :mcp-server:fatJar
```

That writes `mcp-server/build/libs/triplah-mcp.jar`. Every client below starts it the same way: command `java`, arguments `-jar <absolute path to triplah-mcp.jar>`.

Use **absolute paths**. Clients start servers from their own working directory, so relative paths break.

| Environment variable | Value |
|---|---|
| `TRIPLAH_SEED` | absolute path to the repo's `seed` folder (required) |
| `TRIPLAH_DB` | absolute path for the local SQLite file (timetables, watches); default `triplah.db` in the working directory |
| `TRIPLAH_PROVIDERS` | `mock` (default, seed data, no keys) or `real` |
| `SERPAPI_KEY`, `TRAVELPAYOUTS_TOKEN`, `LITEAPI_KEY`, `AERODATABOX_KEY`, `ONEMAP_TOKEN` | only with `TRIPLAH_PROVIDERS=real`; see [OPEN-ITEMS.md](OPEN-ITEMS.md) |

Start with `mock`: it needs no keys and spends no quota. Don't put real keys in a config file that you commit or share; where a client supports it, reference environment variables instead.

In the examples, replace `/ABS/TripLah` with your path. On Windows use forward slashes (`C:/Users/you/TripLah/...`) or escaped backslashes (`C:\\Users\\you\\...`) in JSON. If `java` isn't on your PATH, use the full path to `java` / `java.exe` as the command.

Most clients accept this common block. It's referred to below as **the standard block**:

```json
{
  "mcpServers": {
    "triplah": {
      "command": "java",
      "args": ["-jar", "/ABS/TripLah/mcp-server/build/libs/triplah-mcp.jar"],
      "env": {
        "TRIPLAH_SEED": "/ABS/TripLah/seed",
        "TRIPLAH_DB": "/ABS/TripLah/triplah.db",
        "TRIPLAH_PROVIDERS": "mock"
      }
    }
  }
}
```

After connecting, try: *"Import seed/timetables/traveller-a.ics as Traveller A, then plan the cheapest trip to an award ceremony at the Queen Sirikit centre in Bangkok on 20 Nov 2026, 2-5pm."* For best results also load the skill ([skill/SKILL.md](../skill/SKILL.md)) as instructions, where the client supports it.

## 2. Local clients (stdio)

### Claude Code
[Docs](https://code.claude.com/docs/en/mcp). Inside this repo it's already set up: `.mcp.json` registers `triplah`. Elsewhere:

```bash
claude mcp add --transport stdio --env TRIPLAH_SEED=/ABS/TripLah/seed --env TRIPLAH_DB=/ABS/TripLah/triplah.db triplah -- java -jar /ABS/TripLah/mcp-server/build/libs/triplah-mcp.jar
```

Add the skill: copy `skill/SKILL.md` to `.claude/skills/student-trip-planner/SKILL.md` (project) or `~/.claude/skills/student-trip-planner/SKILL.md` (all projects).

### Claude Desktop
[Docs](https://modelcontextprotocol.io/quickstart/user). Settings → Developer → Edit Config, or edit the file directly:

- macOS: `~/Library/Application Support/Claude/claude_desktop_config.json`
- Windows: `%APPDATA%\Claude\claude_desktop_config.json`
- Linux: `~/.config/Claude/claude_desktop_config.json`

Paste **the standard block**, save, and fully restart Claude Desktop. Logs: `%APPDATA%\Claude\logs` (Windows), `~/Library/Logs/Claude` (macOS).

### Cursor
[Docs](https://cursor.com/docs/context/mcp). Project: `.cursor/mcp.json`. All projects: `~/.cursor/mcp.json`. Use **the standard block** (you may add `"type": "stdio"` to the server entry).

### VS Code (GitHub Copilot, agent mode)
[Docs](https://code.visualstudio.com/docs/copilot/chat/mcp-servers). Workspace: `.vscode/mcp.json`. Note the top-level key is `servers`, not `mcpServers`:

```json
{
  "servers": {
    "triplah": {
      "type": "stdio",
      "command": "java",
      "args": ["-jar", "/ABS/TripLah/mcp-server/build/libs/triplah-mcp.jar"],
      "env": { "TRIPLAH_SEED": "/ABS/TripLah/seed", "TRIPLAH_DB": "/ABS/TripLah/triplah.db" }
    }
  }
}
```

Or add it to your user profile from a terminal:

```bash
code --add-mcp '{"name":"triplah","type":"stdio","command":"java","args":["-jar","/ABS/TripLah/mcp-server/build/libs/triplah-mcp.jar"],"env":{"TRIPLAH_SEED":"/ABS/TripLah/seed"}}'
```

Use it in Copilot Chat in **Agent** mode.

### Cline
[Docs](https://docs.cline.bot/mcp/configuring-mcp-servers). In VS Code: Cline's MCP Servers panel → Configure → Configure MCP Servers, then paste **the standard block**. Cline CLI reads `~/.cline/data/settings/cline_mcp_settings.json` (override with `CLINE_MCP_SETTINGS_PATH`). Optional keys per server: `"disabled": false`, `"autoApprove": []`.

### Continue
[Docs](https://docs.continue.dev/customize/deep-dives/mcp). Create `.continue/mcpServers/triplah.yaml` in your workspace (MCP works in agent mode only):

```yaml
name: TripLah
version: 0.0.1
schema: v1
mcpServers:
  - name: triplah
    command: java
    args:
      - "-jar"
      - "/ABS/TripLah/mcp-server/build/libs/triplah-mcp.jar"
    env:
      TRIPLAH_SEED: "/ABS/TripLah/seed"
      TRIPLAH_DB: "/ABS/TripLah/triplah.db"
```

Continue also picks up a Claude/Cursor-style JSON file (**the standard block**) placed in `.continue/mcpServers/`.

### Zed
[Docs](https://zed.dev/docs/ai/mcp). Run `zed: open settings file` and add (key is `context_servers`):

```json
{
  "context_servers": {
    "triplah": {
      "command": "java",
      "args": ["-jar", "/ABS/TripLah/mcp-server/build/libs/triplah-mcp.jar"],
      "env": { "TRIPLAH_SEED": "/ABS/TripLah/seed", "TRIPLAH_DB": "/ABS/TripLah/triplah.db" }
    }
  }
}
```

Or use Settings → AI → MCP Servers → Add Server → Add Local Server.

### Gemini CLI
[Docs](https://github.com/google-gemini/gemini-cli/blob/main/docs/tools/mcp-server.md). User: `~/.gemini/settings.json`. Project: `.gemini/settings.json`. Use **the standard block**. Or:

```bash
gemini mcp add -e TRIPLAH_SEED=/ABS/TripLah/seed -e TRIPLAH_DB=/ABS/TripLah/triplah.db triplah java -jar /ABS/TripLah/mcp-server/build/libs/triplah-mcp.jar
```

Gemini CLI asks you not to use underscores in server names; `triplah` is fine.

### LM Studio
[Docs](https://lmstudio.ai/docs/app/plugins/mcp). Program tab → Install → Edit `mcp.json`. LM Studio follows Cursor's `mcp.json` format, so paste **the standard block**. Use a model that supports tool calling.

### JetBrains IDEs (AI Assistant)
[Docs](https://www.jetbrains.com/help/ai-assistant/mcp.html). Settings → Tools → AI Assistant → Model Context Protocol (MCP) → Add, then paste:

```json
{ "mcpServers": { "triplah": { "command": "java", "args": ["-jar", "/ABS/TripLah/mcp-server/build/libs/triplah-mcp.jar"] } } }
```

The dialog's template shows no `env`, so set `TRIPLAH_SEED` (and `TRIPLAH_DB`) as system environment variables before starting the IDE, or set the **Working directory** to the repo root so the default `seed/` and `triplah.db` paths resolve. "Import from Claude" reuses your Claude Desktop config.

### Windsurf (unverified)
Windsurf's MCP docs now redirect to Devin's, so this couldn't be checked. Third-party references list `~/.codeium/windsurf/mcp_config.json` with **the standard block**; in the app, Cascade → MCP servers → configure.

### OpenAI Codex CLI (unverified)
The official config reference couldn't be read for this guide. Codex has used `~/.codex/config.toml` with a TOML table per server:

```toml
[mcp_servers.triplah]
command = "java"
args = ["-jar", "/ABS/TripLah/mcp-server/build/libs/triplah-mcp.jar"]
env = { TRIPLAH_SEED = "/ABS/TripLah/seed", TRIPLAH_DB = "/ABS/TripLah/triplah.db" }
```

Recent versions also have `codex mcp add triplah -- java -jar /ABS/TripLah/mcp-server/build/libs/triplah-mcp.jar`. Check the [Codex docs](https://github.com/openai/codex) if either fails.

### Goose (unverified)
Goose's extension docs returned 404 when this guide was written. In Goose, add a **Command-line extension** (`goose configure` → Add Extension, or Extensions in the desktop app) with command `java -jar /ABS/TripLah/mcp-server/build/libs/triplah-mcp.jar` and the environment variables above.

### Anything else
Any MCP client that can launch a stdio server works: command `java`, args `["-jar", "/ABS/.../triplah-mcp.jar"]`, env as above. The server implements protocol versions `2025-06-18`, `2025-03-26` and `2024-11-05` (`initialize`, `ping`, `tools/list`, `tools/call`).

## 3. Remote clients (ChatGPT, Claude.ai)

Web apps can't launch a program on your computer. They need the server at a **public HTTPS URL**:

- **ChatGPT:** "a public HTTPS endpoint" that "supports streamable HTTP, typically at `/mcp`", or OpenAI's Secure MCP Tunnel, which can reach a local stdio server. Add it under ChatGPT Plugins → **+** → Add custom MCP server ([docs](https://developers.openai.com/apps-sdk/deploy/connect-chatgpt)).
- **Claude.ai:** a custom connector must be "reachable over the public internet from Anthropic's IP ranges". Local servers from `claude_desktop_config.json` aren't available there. Add it under Customize → Connectors → **+ Add** → Add custom connector ([docs](https://support.claude.com/en/articles/11175166-getting-started-with-custom-connectors-using-remote-mcp)).

To serve TripLah over HTTP, run it behind [supergateway](https://github.com/supercorp-ai/supergateway) (needs Node.js). It exposes streamable HTTP at `http://localhost:8000/mcp`:

```bash
npx -y supergateway --stdio "java -jar /ABS/TripLah/mcp-server/build/libs/triplah-mcp.jar" --outputTransport streamableHttp --port 8000
```

Set `TRIPLAH_SEED` and `TRIPLAH_DB` in that terminal first, since supergateway passes its environment to the server. On Windows, write the jar path as `C:/Users/...`: Git Bash's `/c/Users/...` form makes Java fail with "Unable to access jarfile". (Tested on 6 Nov 2026: `initialize`, `tools/list` with 11 tools, and `tools/call` all work through the bridge.) Then publish port 8000 over HTTPS with a tunnel you control (for example `cloudflared tunnel --url http://localhost:8000`) and give the client `https://<your-tunnel-host>/mcp`.

**Before you expose it:** TripLah has no login of its own, and anyone with the URL can call its tools, including your imported timetables. Prefer ChatGPT's Secure MCP Tunnel where available; otherwise keep the tunnel short-lived, use `TRIPLAH_PROVIDERS=mock` (no API keys on the server), and stop it when you're done. supergateway's `--apiKey` option adds a shared key, but only if your client can send it.

## 4. Without MCP: the REST API

Clients that call HTTP APIs instead (custom GPT Actions, scripts, agent frameworks) can use the REST API. It serves the same tools, with an OpenAPI 3.1 spec at `/openapi.json`:

```bash
./gradlew :api:run
```

The API listens on `http://127.0.0.1:8080` (tools at `POST /v1/tools/{name}`). The MCP server can also forward to it: `java -jar triplah-mcp.jar --api http://127.0.0.1:8080`. [COMPARISON.md](COMPARISON.md) measures how Claude does with this generic route against the MCP tools.

## Troubleshooting

- **Server doesn't start / no tools listed:** run the jar by hand and send one line. You should see a JSON reply that includes `"serverInfo":{"name":"triplah"`:

```bash
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{}}}' | java -jar /ABS/TripLah/mcp-server/build/libs/triplah-mcp.jar
```

- **`seed/ not found`:** set `TRIPLAH_SEED` to the absolute path of `seed`.
- **`java` not found:** use the full path to the JDK 21 `java` executable as the command.
- **Timetable import fails with a path:** pass an absolute path to `import_timetable`, or paste the file content.
- **Logs:** the server writes logs to stderr only (stdout carries the protocol). Your client's MCP log shows them.

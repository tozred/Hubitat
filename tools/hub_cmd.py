#!/usr/bin/env python3
"""Call a tool on a Hubitat hub's MCP endpoint without an MCP client (uses the token in ~/.claude.json).

Usage: hub_cmd.py [--server hubitat] TOOL '{"json":"args"}'
  e.g. hub_cmd.py run_device_command '{"deviceId":35,"command":"logsOff"}'
"""
import argparse
import json
import os
import sys
import urllib.request


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--server", default="hubitat")
    ap.add_argument("tool")
    ap.add_argument("args", nargs="?", default="{}")
    a = ap.parse_args()
    cfg = json.load(open(os.path.expanduser("~/.claude.json")))["mcpServers"][a.server]
    headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream", **cfg.get("headers", {})}

    def post(body, sid=None):
        h = dict(headers, **({"Mcp-Session-Id": sid} if sid else {}))
        r = urllib.request.urlopen(urllib.request.Request(cfg["url"], data=json.dumps(body).encode(), headers=h), timeout=40)
        raw = r.read().decode()
        return r.headers.get("Mcp-Session-Id"), (json.loads(raw) if raw.strip().startswith("{") else raw)

    sid, _ = post({"jsonrpc": "2.0", "id": 1, "method": "initialize",
                   "params": {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "hub_cmd", "version": "1"}}})
    post({"jsonrpc": "2.0", "method": "notifications/initialized"}, sid)
    _, res = post({"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {"name": a.tool, "arguments": json.loads(a.args)}}, sid)
    out = res.get("result", res) if isinstance(res, dict) else res
    text = "".join(c.get("text", "") for c in out.get("content", [])) if isinstance(out, dict) and "content" in out else json.dumps(out)
    print(text[:4000])
    return 0


if __name__ == "__main__":
    sys.exit(main())

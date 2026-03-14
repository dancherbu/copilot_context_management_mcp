# Repository Instructions

- Primary runtime is Docker Compose. Prefer Compose for build, run, and smoke tests.
- This service is a standalone Spring Boot MCP server exposed over SSE behind Nginx Proxy Manager.
- Keep `~/Projects` mounted read-only into the app container unless an explicit write-back requirement is added.
- Store any local-only secrets in `copilot-secrets.md`; never commit secrets.
- Prefer proxy-domain smoke tests against `ccm-mcp.local` once Nginx Proxy Manager is configured.
- Avoid cloud AI services unless explicitly approved. Default to local Ollama.

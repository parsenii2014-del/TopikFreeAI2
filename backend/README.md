# FreeAI Router v0.4

OpenAI-совместимый роутер с агентами, web-search и памятью.

```bash
pip install -r requirements.txt
cp .env.example .env
python server.py
```

Ключевые эндпоинты:
- `POST /v1/chat/completions` — основной (поддерживает `mode: agents|code|tools|chat`)
- `GET  /v1/models`
- `POST /v1/search`
- `GET/DELETE /v1/memory`

Агент сам вызывает `web_search`, когда нужно.

#!/usr/bin/env python3
"""
FreeAI Router v0.4 — powerful OpenAI-compatible backend
=======================================================
• Real multi-step Agents with tool use
• Web search (DuckDuckGo)
• Long-term user memory + conversation memory
• Smart model routing
• Streaming
• Conversation management
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import time
import uuid
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, AsyncGenerator, Dict, List, Optional, Tuple

import httpx
from bs4 import BeautifulSoup
from dotenv import load_dotenv
from fastapi import FastAPI, Header, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

load_dotenv()

# ─── Config ──────────────────────────────────────────────────────────────────
API_KEYS = set(k.strip() for k in os.getenv("FREEAI_API_KEYS", "freeai-demo-key").split(",") if k.strip())
UPSTREAM_BASE = os.getenv("UPSTREAM_BASE_URL", "").rstrip("/")
UPSTREAM_KEY = os.getenv("UPSTREAM_API_KEY", "")
MEMORY_DIR = Path(os.getenv("MEMORY_DIR", "./memory"))
MEMORY_DIR.mkdir(parents=True, exist_ok=True)
PORT = int(os.getenv("PORT", "3001"))
MAX_AGENT_STEPS = int(os.getenv("MAX_AGENT_STEPS", "6"))

MODEL_MAP = {
    "auto": "gpt-4o-mini",
    "auto:fast": "gpt-4o-mini",
    "auto:smart": "gpt-4o",
    "auto:reliable": "gpt-4o",
}

FALLBACK_MODELS = [
    {"id": m, "object": "model"} for m in
    ["auto", "auto:fast", "auto:smart", "auto:reliable", "gpt-4o-mini", "gpt-4o", "claude-3-5-sonnet", "deepseek-chat"]
]

app = FastAPI(title="FreeAI Router", version="0.4.0")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_credentials=True, allow_methods=["*"], allow_headers=["*"])

conversations: Dict[str, List[Dict[str, str]]] = defaultdict(list)


# ─── Auth & helpers ──────────────────────────────────────────────────────────
def require_key(authorization: Optional[str] = Header(None)) -> str:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "Missing Authorization")
    key = authorization[7:].strip()
    if key not in API_KEYS:
        raise HTTPException(401, "Invalid API key")
    return key


def user_hash(key: str) -> str:
    return hashlib.sha256(key.encode()).hexdigest()[:16]


def load_mem(uh: str) -> Dict[str, Any]:
    p = MEMORY_DIR / f"{uh}.json"
    if p.exists():
        try:
            return json.loads(p.read_text(encoding="utf-8"))
        except Exception:
            pass
    return {"facts": [], "style": "", "updated": None}


def save_mem(uh: str, data: Dict[str, Any]) -> None:
    data["updated"] = datetime.now(timezone.utc).isoformat()
    (MEMORY_DIR / f"{uh}.json").write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def classify_query(text: str) -> str:
    t = text.lower()
    if any(w in t for w in ("код", "code", "функци", "баг", "ошибк", "python", "kotlin", "javascript", "api", "sql", "refactor", "debug")):
        return "code"
    if any(w in t for w in ("агент", "спланируй", "по шагам", "выполни задачу", "разбей", "plan", "agent")):
        return "agent"
    if any(w in t for w in ("найди", "поиск", "search", "что такое", "кто такой", "актуальн", "новост")):
        return "search"
    return "chat"


# ─── Web Search Tool ─────────────────────────────────────────────────────────
async def tool_web_search(query: str, max_results: int = 5) -> str:
    """DuckDuckGo HTML search — no API key needed."""
    try:
        url = "https://html.duckduckgo.com/html/"
        async with httpx.AsyncClient(timeout=12.0, follow_redirects=True) as client:
            r = await client.post(url, data={"q": query}, headers={
                "User-Agent": "Mozilla/5.0 (compatible; FreeAI/0.4)"
            })
            if r.status_code != 200:
                return f"[search error: HTTP {r.status_code}]"
            soup = BeautifulSoup(r.text, "lxml")
            results = []
            for res in soup.select(".result")[:max_results]:
                title_el = res.select_one(".result__a")
                snippet_el = res.select_one(".result__snippet")
                if not title_el:
                    continue
                title = title_el.get_text(strip=True)
                link = title_el.get("href", "")
                snippet = snippet_el.get_text(strip=True) if snippet_el else ""
                results.append(f"• {title}\n  {snippet}\n  {link}")
            if not results:
                return "Ничего не найдено."
            return "Результаты поиска:\n\n" + "\n\n".join(results)
    except Exception as e:
        return f"[search failed: {e}]"


# ─── Upstream call ───────────────────────────────────────────────────────────
async def call_upstream(
    messages: List[Dict[str, str]],
    model: str,
    stream: bool = False,
    temperature: float = 0.7,
) -> Any:
    if not UPSTREAM_BASE or not UPSTREAM_KEY:
        return None  # demo mode

    resolved = MODEL_MAP.get(model, model)
    payload = {
        "model": resolved,
        "messages": messages,
        "stream": stream,
        "temperature": temperature,
    }
    headers = {"Authorization": f"Bearer {UPSTREAM_KEY}", "Content-Type": "application/json"}

    if stream:
        return payload, headers  # caller will stream

    async with httpx.AsyncClient(timeout=180.0) as client:
        r = await client.post(f"{UPSTREAM_BASE}/chat/completions", headers=headers, json=payload)
        if r.status_code >= 400:
            raise HTTPException(r.status_code, r.text[:600])
        return r.json()


async def stream_upstream(payload: dict, headers: dict) -> AsyncGenerator[bytes, None]:
    async with httpx.AsyncClient(timeout=180.0) as client:
        async with client.stream("POST", f"{UPSTREAM_BASE}/chat/completions", headers=headers, json=payload) as r:
            async for chunk in r.aiter_bytes():
                yield chunk


# ─── Strong system prompts ───────────────────────────────────────────────────
SYSTEM_CHAT = """Ты FreeAI — один из самых сильных диалоговых ассистентов.
Будь точным, полезным, честным и приятным. Не выдумывай факты.
Если нужна свежая информация — скажи, что можешь использовать поиск.
Отвечай на языке пользователя. Структурируй ответы, когда это помогает."""

SYSTEM_CODE = """Ты элитный software engineer уровня Claude Opus / GPT-o1.
Правила:
1. Пиши рабочий, идиоматичный, безопасный код.
2. Всегда объясняй ключевые решения коротко.
3. Если видишь баг — находи корневую причину, а не симптом.
4. Не выдумывай результаты запуска или API, которых нет.
5. Форматируй код в markdown-блоках с указанием языка.
6. Предлагай тесты и edge-cases, когда уместно.
7. Если задача большая — сначала план, потом код.
Отвечай на языке пользователя."""

SYSTEM_AGENT = """Ты автономный агент-исполнитель FreeAI.
Ты умеешь:
- Разбивать сложные задачи на шаги
- Использовать инструменты (web_search)
- Проверять промежуточные результаты
- Останавливаться и спрашивать, когда нужно подтверждение

Формат работы:
1. Краткий план (3–7 шагов)
2. Выполнение по шагам с использованием инструментов при необходимости
3. Итоговый результат + что ещё можно улучшить

Никогда не утверждай, что выполнил действие во внешнем мире, если этого не было.
Отвечай на языке пользователя."""

SYSTEM_SEARCH = """Ты исследователь. Сначала используй актуальные данные из поиска, потом делай выводы.
Всегда указывай источники. Не придумывай новости или цифры."""


# ─── Agent loop ──────────────────────────────────────────────────────────────
async def run_agent(user_messages: List[Dict[str, str]], model: str, uh: str) -> str:
    """Multi-step agent with tool use."""
    mem = load_mem(uh)
    memory_block = ""
    if mem.get("facts"):
        memory_block = "Известные факты о пользователе:\n- " + "\n- ".join(mem["facts"][:10])

    system = SYSTEM_AGENT
    if memory_block:
        system += "\n\n" + memory_block

    messages = [{"role": "system", "content": system}] + user_messages
    steps_log: List[str] = []
    final_answer = ""

    for step in range(MAX_AGENT_STEPS):
        # Ask model what to do next
        prompt_extra = (
            f"\n\n[Шаг {step + 1}/{MAX_AGENT_STEPS}]\n"
            "Если нужно искать информацию в интернете — ответь строго в формате:\n"
            "TOOL: web_search\nQUERY: <поисковый запрос>\n"
            "Если задача решена — дай финальный ответ без TOOL."
        )
        step_messages = messages + [{"role": "user", "content": prompt_extra}]

        data = await call_upstream(step_messages, model, stream=False, temperature=0.4)
        if data is None:
            # demo agent
            return (
                "### План (demo)\n1. Проанализировать задачу\n2. При необходимости поискать данные\n"
                "3. Собрать ответ\n\n"
                "**Demo-режим**: подключи UPSTREAM_BASE_URL и UPSTREAM_API_KEY, "
                "чтобы агент реально ходил в поиск и вызывал модели."
            )

        content = data["choices"][0]["message"]["content"]
        steps_log.append(content)

        # Check for tool call
        tool_match = re.search(r"TOOL:\s*web_search\s*\nQUERY:\s*(.+)", content, re.I)
        if tool_match:
            query = tool_match.group(1).strip()
            search_result = await tool_web_search(query)
            messages.append({"role": "assistant", "content": content})
            messages.append({
                "role": "user",
                "content": f"Результат инструмента web_search по запросу «{query}»:\n\n{search_result}\n\nПродолжай."
            })
            continue

        # No tool — treat as final
        final_answer = content
        break
    else:
        final_answer = steps_log[-1] if steps_log else "Достигнут лимит шагов агента."

    # Light memory update
    if "запомни" in final_answer.lower() or "меня зовут" in final_answer.lower():
        facts = [s.strip() for s in re.split(r"[.!?]", final_answer) if len(s.strip()) > 15][:2]
        if facts:
            mem["facts"] = list(dict.fromkeys(mem.get("facts", []) + facts))[-20:]
            save_mem(uh, mem)

    return final_answer


# ─── Models ──────────────────────────────────────────────────────────────────
class ChatMessage(BaseModel):
    role: str
    content: str


class ChatRequest(BaseModel):
    model: str = "auto"
    messages: List[ChatMessage]
    stream: bool = False
    temperature: Optional[float] = 0.7
    max_tokens: Optional[int] = None
    conversation_id: Optional[str] = None
    # FreeAI extensions
    mode: Optional[str] = None          # chat | code | agents | tools
    enable_tools: Optional[bool] = True


# ─── Routes ──────────────────────────────────────────────────────────────────
@app.get("/")
async def root():
    return {
        "name": "FreeAI Router",
        "version": "0.4.0",
        "features": ["agents", "web_search", "memory", "streaming", "smart_routing"],
        "endpoints": ["/v1/models", "/v1/chat/completions", "/v1/memory", "/v1/search"],
    }


@app.get("/v1/models")
async def list_models(authorization: Optional[str] = Header(None)):
    require_key(authorization)
    models = list(FALLBACK_MODELS)
    if UPSTREAM_BASE and UPSTREAM_KEY:
        try:
            async with httpx.AsyncClient(timeout=12.0) as client:
                r = await client.get(f"{UPSTREAM_BASE}/models", headers={"Authorization": f"Bearer {UPSTREAM_KEY}"})
                if r.status_code == 200:
                    for m in r.json().get("data", []):
                        mid = m.get("id")
                        if mid and not any(x["id"] == mid for x in models):
                            models.append({"id": mid, "object": "model"})
        except Exception:
            pass
    return {"object": "list", "data": models}


@app.post("/v1/chat/completions")
async def chat_completions(body: ChatRequest, authorization: Optional[str] = Header(None)):
    key = require_key(authorization)
    uh = user_hash(key)

    last_user = next((m.content for m in reversed(body.messages) if m.role == "user"), "")
    mode = (body.mode or "").lower()
    if not mode:
        mode = classify_query(last_user)

    # Agents path
    if mode in ("agents", "agent") or body.model.startswith("auto") and "агент" in last_user.lower():
        reply = await run_agent(
            [{"role": m.role, "content": m.content} for m in body.messages if m.role != "system"],
            body.model,
            uh,
        )
        return {
            "id": f"chatcmpl-{uuid.uuid4().hex[:10]}",
            "object": "chat.completion",
            "created": int(time.time()),
            "model": body.model,
            "choices": [{"index": 0, "message": {"role": "assistant", "content": reply}, "finish_reason": "stop"}],
            "usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0},
        }

    # Build system prompt
    if mode == "code":
        system = SYSTEM_CODE
    elif mode in ("search", "tools"):
        system = SYSTEM_SEARCH
    else:
        system = SYSTEM_CHAT

    mem = load_mem(uh)
    if mem.get("facts"):
        system += "\n\nИзвестные факты о пользователе:\n- " + "\n- ".join(mem["facts"][:8])
    if mem.get("style"):
        system += f"\nПредпочитаемый стиль: {mem['style']}"

    # Optional automatic search for search-like queries
    messages: List[Dict[str, str]] = [{"role": "system", "content": system}]
    for m in body.messages:
        if m.role == "system":
            continue  # we already set a strong system
        messages.append({"role": m.role, "content": m.content})

    if mode in ("search", "tools") or (body.enable_tools and classify_query(last_user) == "search"):
        search_result = await tool_web_search(last_user[:200])
        messages.insert(1, {
            "role": "system",
            "content": f"Актуальные данные из интернета по запросу пользователя:\n\n{search_result}"
        })

    # Demo fallback
    if not UPSTREAM_BASE or not UPSTREAM_KEY:
        return demo_reply(body, mode, last_user, uh)

    # Streaming
    if body.stream:
        payload, headers = await call_upstream(messages, body.model, stream=True, temperature=body.temperature or 0.7)
        return StreamingResponse(stream_upstream(payload, headers), media_type="text/event-stream")

    data = await call_upstream(messages, body.model, stream=False, temperature=body.temperature or 0.7)
    if data is None:
        return demo_reply(body, mode, last_user, uh)

    # Memory update heuristic
    try:
        reply = data["choices"][0]["message"]["content"]
        if any(x in reply.lower() for x in ("меня зовут", "запомни", "я предпочитаю")):
            facts = [s.strip()[:180] for s in re.split(r"[.!?]", reply) if len(s.strip()) > 20][:2]
            if facts:
                mem["facts"] = list(dict.fromkeys(mem.get("facts", []) + facts))[-20:]
                save_mem(uh, mem)
    except Exception:
        pass

    return data


@app.post("/v1/search")
async def search_endpoint(request: dict, authorization: Optional[str] = Header(None)):
    require_key(authorization)
    q = request.get("query") or request.get("q") or ""
    if not q:
        raise HTTPException(400, "query required")
    result = await tool_web_search(q)
    return {"query": q, "result": result}


@app.get("/v1/memory")
async def get_memory(authorization: Optional[str] = Header(None)):
    key = require_key(authorization)
    return load_mem(user_hash(key))


@app.delete("/v1/memory")
async def clear_memory(authorization: Optional[str] = Header(None)):
    key = require_key(authorization)
    p = MEMORY_DIR / f"{user_hash(key)}.json"
    if p.exists():
        p.unlink()
    return {"ok": True}


def demo_reply(body: ChatRequest, mode: str, last_user: str, uh: str) -> dict:
    mem = load_mem(uh)
    reply = (
        f"**FreeAI Demo · mode={mode}**\n\n"
        f"Получил: «{last_user[:250]}»\n\n"
        "Сейчас нет UPSTREAM_BASE_URL / UPSTREAM_API_KEY — поэтому это demo-ответ.\n\n"
        "Чтобы получить реальную силу:\n"
        "1. В `.env` укажи провайдера (OpenAI, OpenRouter, DeepSeek, Groq…)\n"
        "2. Перезапусти сервер\n"
        "3. В Android укажи URL этого роутера\n\n"
        f"Память сервера: {len(mem.get('facts', []))} фактов.\n"
        "Агенты, поиск и умная маршрутизация уже реализованы и заработают сразу после подключения upstream."
    )
    return {
        "id": f"chatcmpl-demo-{uuid.uuid4().hex[:8]}",
        "object": "chat.completion",
        "created": int(time.time()),
        "model": body.model,
        "choices": [{"index": 0, "message": {"role": "assistant", "content": reply}, "finish_reason": "stop"}],
        "usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0},
    }


if __name__ == "__main__":
    import uvicorn
    print(f"FreeAI Router v0.4 on :{PORT}")
    print(f"Keys: {API_KEYS}")
    print(f"Upstream: {UPSTREAM_BASE or 'DEMO MODE'}")
    uvicorn.run(app, host="0.0.0.0", port=PORT)

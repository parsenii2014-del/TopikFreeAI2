# FreeAI v0.4 — агенты, поиск, память, сильный код

Это уже не просто чат-клиент.

## Что умеет

### Backend (`backend/`)
- **Настоящие Agents** — multi-step цикл с tool-use (до 6 шагов)
- **Web Search** — DuckDuckGo без ключей
- **Умная маршрутизация** — auto / fast / smart / reliable + классификация запроса
- **Долговременная память** пользователя (факты привязаны к API-ключу)
- **Сильные системные промпты** для Chat / Code / Agents / Search
- OpenAI-совместимый API + streaming
- Demo-режим (работает без upstream) и полный режим с любым провайдером

### Android
- Режимы: **Чат · Code · Agents · Tools · Специализации**
- **Мульти-чаты** с историей (до 30 сессий, шифруется на устройстве)
- Рендер code-блоков моноширинным шрифтом
- Long-press → копирование
- Автоскролл, сохранение всего состояния
- Отправка `mode` на backend → агенты и поиск работают по-настоящему

## Быстрый старт

### 1. Backend
```bash
cd backend
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
# Для реальной силы раскомментируй и заполни:
# UPSTREAM_BASE_URL=https://api.openai.com/v1
# UPSTREAM_API_KEY=sk-...
python server.py
```

### 2. Android
- Залей в GitHub → Actions → **Build FreeAI APK**
- Или Android Studio

### 3. Подключение
В ⚙ приложения:
- URL: `http://<твой-сервер>:3001/v1`
- Ключ: `freeai-demo-key` (или свой из `.env`)

`adb reverse tcp:3001 tcp:3001` — если сервер на компьютере, а клиент в эмуляторе.

## Почему это сильно

| Режим     | Что происходит                                      |
|-----------|-----------------------------------------------------|
| **Code**  | Элитный system prompt + форматирование блоков кода  |
| **Agents**| Backend запускает multi-step loop + web_search      |
| **Tools** | Автоматический поиск + усиленный research prompt    |
| **Чат**   | Память + точные ответы + классификация запросов     |

## Структура
```
├── app/                 # Android
├── backend/
│   ├── server.py        # Роутер + агенты + поиск
│   ├── requirements.txt
│   ├── Dockerfile
│   └── .env.example
├── .github/workflows/   # Сборка APK
└── README.md
```

## Дальше
Стриминг в UI, vector memory, code execution sandbox, diff-view — можно наращивать поверх этого ядра.

**v0.4.0**

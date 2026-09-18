# TokenG API Gateway Service (`tokeng-server` v6.2.0)

A high-concurrency, ultra-lightweight REST API gateway written in Go that securely connects to the remote TokenG PostgreSQL database (`169.58.138.108:25432`). Version-aligned with TokenG Android app **v6.2.0**.

## Security Architecture
- **Zero Client Credentials**: No PostgreSQL credentials or drivers reside in the Android client APK.
- **Least-Privilege Database Role**: Runs as `tokeng_api_user` with strictly zero DDL privileges, bounded connection limit (30), and statement timeout (5s).
- **Defense in Depth**:
  - BCrypt password hashing (cost 10)
  - HMAC-SHA256 Bearer JWT authentication
  - Per-IP rate limiting (10 req/s, burst 20)
  - 1MB max request payload cap
  - 10-second request timeout
  - Strict tenant isolation enforced in Go (`user_id` forced from JWT claims)

## Docker Quickstart

```bash
# 1. Inspect or configure .env
cp .env.example .env

# 2. Build and start with Docker Compose
docker compose up -d --build

# 3. Check health and logs
docker compose ps
docker compose logs -f
```

## API Endpoints

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| `GET` | `/health` | Public | Healthcheck and PostgreSQL pool ping |
| `POST` | `/api/auth/register` | Public | Register new Gmail + password |
| `POST` | `/api/auth/login` | Public | Login with Gmail + password, returns JWT |
| `GET` | `/api/sync/pull?since=...` | Bearer JWT | Delta pull instances modified since timestamp |
| `POST` | `/api/sync/push` | Bearer JWT | Delta push/upsert token instances |
| `GET` | `/api/stats` | Public | Global system statistics |

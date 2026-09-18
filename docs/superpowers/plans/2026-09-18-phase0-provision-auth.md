# Phase 0 Provision Auth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close S1–S3 (and S4/S7 in-tree): bearer admin token, per-user device token, cascade secret, localhost publish, TLS fingerprint, audit, rate limit, telemetry upload token — without changing the SSH stdout protocol.

**Architecture:** Credentials and TLS live in `/data`. Host compose bind defaults to `127.0.0.1`. Provision still listens `0.0.0.0:9100` inside the netns. HTTP and HTTPS share port 9100 via ClientHello detect. Auth middleware sits in front of the existing mux.

**Tech Stack:** Go 1.22 provision, bash installer, Flask telemetry, Docker Compose bind.

## Global Constraints

- Upgrade from 1.0.53 must keep `data/` (users, keys, warp).
- Do not touch foreign containers/networks/Engine/firewall.
- No Android except `assets/deploy/DEPLOY_VERSION` and `DeployBundle.FALLBACK_VERSION`.
- Stdout markers `ARDTT_PROGRESS|` / `ARDTT_DONE|` stay in the first 400 chars of `install.sh`.
- `DEPLOY_VERSION` 1.0.54 in lockstep with assets and FALLBACK_VERSION.
- Secrets never in `.env`, compose environment, or logs. `admin_token` only on first `ARDTT_DONE`.
- `X-Forwarded-For` is not an auth source.

---

### Task 1: Provision auth + TLS + audit + rate limit

**Files:**
- Create: `server/provision/auth.go`, `tls.go`, `audit.go`, `ratelimit.go` and `*_test.go`
- Modify: `server/provision/main.go` (User/Profile.DeviceToken, `newAPIMux`, `runServer`)

- [x] Write failing Go tests, then implement until `go test ./server/provision` is green.

### Task 2: Bind 127.0.0.1 + installer secrets + ARDTT_DONE

**Files:**
- Create: `server/install-lib/secrets.sh`, `scripts/test-install-provision-secrets.sh`
- Modify: `server/docker-compose.yml`, `server/docker-compose.exit.yml`, `server/install.sh`, `server/.env.example`, `server/warp/entrypoint.sh`, `server/entrypoint.sh`

- [x] Host publish bind, token files 0600, cascade Authorization on peer push and hide-ip poll.

### Task 3: Telemetry token, 20 MB, quota, no loopback review bypass

**Files:**
- Modify: `server/telemetry-upload/app.py`, `server/telemetry-upload/test_app.py`

### Task 4: Docs, version bump, verify

**Files:**
- `server/DEPLOY_VERSION`, `android/app/src/main/assets/deploy/DEPLOY_VERSION`, `DeployBundle.kt` + test, `docs/DEPLOY.md`, `docs/TELEMETRY.md`, `CHANGELOG.md`, `server/README.md`

- [x] `go test`, telemetry unittest, secrets installer test, `check-deploy-bundle.sh`.

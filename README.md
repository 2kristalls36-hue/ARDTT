# nonameVPN

VPN solution with dual connection paths: direct AmneziaWG 2.0 and RAW bypass via TURN infrastructure.

## Quick Start

### Server Setup
```bash
cd server
cp .env.example .env
docker compose up -d --build
./scripts/create-user.sh username
```

### Android Client
```bash
cd android
./gradlew assembleDebug
```

APK location: `app/build/outputs/apk/debug/app-debug.apk`

## Architecture

- **Direct Path**: AmneziaWG 2.0 (fast, UDP-based)
- **Bypass Path**: RAW dial via TURN (when UDP is blocked)
- **Hide IP**: Optional Cloudflare WARP egress

## Project Structure

```
nonameVPN/
├── android/          # Jetpack Compose client
├── server/           # Docker Compose stack
├── docs/            # Documentation
└── README.md
```

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Deployment Guide](docs/DEPLOY.md)
- [License](LICENSE)

## License

GNU GPL v3

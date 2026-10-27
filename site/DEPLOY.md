# Free Render web service

This service hosts only the Sharable account/download portal. File transfers remain on the local Wi-Fi server. Do not route file bytes to this service.

- Root directory: `site`
- Build: `npm ci`
- Start: `npm start`
- Instance: Free. No keep-alive monitor or background pings.
- Health check: `/api/health`
- Secret environment variable: `MONGODB_PASSWORD` (set only in the host's secret form)
- Non-secret environment: `MONGODB_USER=sharable_app`, `MONGODB_HOST=sharable.mrekhty.mongodb.net`, `MONGODB_DB=sharable`
- Alternatively, supply `MONGODB_URI` as one secret. Never commit real credentials.

The database user needs `readWrite` on `sharable`, not Atlas admin. Accounts use scrypt hashes and hashed opaque session IDs, HttpOnly/SameSite cookies with Secure behind HTTPS, and MongoDB TTL session expiry. Only account and session data are stored. No email verification or password-reset feature is included in this test build. Do not reuse an important password.

# Sharable

Fast local file transfer over Wi-Fi or a phone hotspot. No cloud, no cable.

## Run on a laptop
    node server.js [folder] [port]
It prints a PIN and the address to open from a phone or another laptop on the same network.

## What it does
- Streams uploads straight to disk in resumable pieces (no whole file in memory)
- Range downloads, so an interrupted download can resume
- PIN protected, local network only

Status: core server and web UI. Android app and large-file Wi-Fi benchmarks are in progress; speed numbers will be published only after real device tests.

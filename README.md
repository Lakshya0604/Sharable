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

## Standalone laptop build (no Node needed)
`./build-exe.sh` makes `dist/sharable-linux-x64` and `dist/sharable-win-x64.exe`.
Tested: Linux build starts, serves the page from inside the single file, and the API responds.
Not tested: the Windows .exe has been built but not run on Windows; macOS is not built.
Windows may show a firewall prompt: allow private networks so a phone can connect.

#!/bin/sh
# Builds standalone executables (no Node needed on the target). Needs Node 22 and: npm i postject
# Usage: ./build-exe.sh   -> dist/sharable-linux-x64, dist/sharable-win-x64.exe
set -e; V=$(node -p process.version); mkdir -p dist; node --experimental-sea-config sea-config.json
SENT=NODE_SEA_FUSE_fce680ab2cc467b6e072b8b5df1996b2
cp "$(readlink -f "$(command -v node)")" dist/sharable-linux-x64
curl -sfL -o /tmp/node-win.zip https://nodejs.org/dist/$V/node-$V-win-x64.zip && unzip -qo -j /tmp/node-win.zip "node-$V-win-x64/node.exe" -d dist && mv dist/node.exe dist/sharable-win-x64.exe
for f in dist/sharable-linux-x64 dist/sharable-win-x64.exe; do npx postject "$f" NODE_SEA_BLOB sea-prep.blob --sentinel-fuse $SENT; done

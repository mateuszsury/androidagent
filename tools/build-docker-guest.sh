#!/usr/bin/env bash
# Guest packaging using a pinned Alpine base; proxy CA stays in a build mount.
set -euo pipefail
cd "$(dirname "$0")/.."
GUEST_ARCH=${1:-x86_64}
case "$GUEST_ARCH" in
 x86_64) PLATFORM=linux/amd64; ABI=x86_64; DIGEST=1f3591b8a02ea153f41c5bba878ad477f63ab3d19349762cb77504db02a23e15;;
 aarch64) PLATFORM=linux/arm64; ABI=arm64-v8a; DIGEST=ae959799024a09da597284763c417a6e820e020fad0634c5267cf4f125e682bf;;
 *) exit 2;;
esac
BASE_IMAGE=alpine@sha256:$DIGEST
OUT="$PWD/app/src/main/assets/docker/$ABI"
STAGING="$PWD/build-runtime/guest-$ABI"
mkdir -p "$OUT" "$STAGING"
DOCKER=(env -u DOCKER_HOST -u DOCKER_CONTEXT -u DOCKER_TLS -u DOCKER_TLS_VERIFY -u DOCKER_CERT_PATH docker --host=unix:///var/run/docker.sock)
CA_ARGS=()
if [ -n "${CODEX_PROXY_CERT:-}" ]; then
  CA_ARGS=(-v /etc/ssl/certs/ca-certificates.crt:/run/proxy-ca.pem:ro -e SSL_CERT_FILE=/run/proxy-ca.pem)
fi
# A regular container builds a root filesystem; it never mounts a host device.
if [ "$GUEST_ARCH" = aarch64 ]; then
  "${DOCKER[@]}" run --rm -v "$STAGING:/out" alpine@sha256:1f3591b8a02ea153f41c5bba878ad477f63ab3d19349762cb77504db02a23e15 sh -ec 'if [ -d /out/arm-keys ]; then chmod 755 /out/arm-keys; chown -R "$1:$2" /out/arm-keys; fi' sh "$(id -u)" "$(id -g)"
  CID=$("${DOCKER[@]}" create --platform "$PLATFORM" "$BASE_IMAGE")
  IMAGE_ID=$("${DOCKER[@]}" inspect --format '{{.Image}}' "$CID")
  mkdir -p "$STAGING/arm-keys"
  "${DOCKER[@]}" cp "$CID:/etc/apk/keys/." "$STAGING/arm-keys/"
  "${DOCKER[@]}" save "$IMAGE_ID" > "$STAGING/alpine-base.tar"
  printf '%s\n' "$IMAGE_ID" > "$STAGING/alpine-base-id"
  "${DOCKER[@]}" rm "$CID" >/dev/null
  "${DOCKER[@]}" run --rm "${CA_ARGS[@]}" -v "$PWD/docker:/input:ro" -v "$STAGING:/out" alpine@sha256:1f3591b8a02ea153f41c5bba878ad477f63ab3d19349762cb77504db02a23e15 sh /input/build-arm-rootfs.sh
else
CID=$("${DOCKER[@]}" create --platform "$PLATFORM" "${CA_ARGS[@]}" -v "$PWD/docker/guest:/input:ro" -v "$STAGING:/out" "$BASE_IMAGE" sh -ec '
  apk add --no-cache alpine-base linux-virt docker docker-cli-buildx docker-cli-compose python3 curl ca-certificates
  mkdir -p /opt/androidagent /var/lib/androidagent/tasks
  cp /input/agent.py /opt/androidagent/agent.py
  cp -r /input/python /opt/androidagent/python
  chmod -R a+rX /opt/androidagent/python
  cp /input/init.sh /opt/androidagent/init.sh
  cp /out/alpine-base.tar /out/alpine-base-id /opt/androidagent/
  chmod 755 /opt/androidagent/init.sh
  cp /boot/vmlinuz-virt /boot/initramfs-virt /out/
  apk info -v > /out/packages.txt
  cp /lib/apk/db/installed /out/apk-installed
  chmod 644 /out/*
')
trap '"${DOCKER[@]}" rm -f "$CID" >/dev/null' EXIT
IMAGE_ID=$("${DOCKER[@]}" inspect --format '{{.Image}}' "$CID")
"${DOCKER[@]}" save "$IMAGE_ID" > "$STAGING/alpine-base.tar"
printf '%s\n' "$IMAGE_ID" > "$STAGING/alpine-base-id"
"${DOCKER[@]}" start -a "$CID"
"${DOCKER[@]}" export "$CID" > "$STAGING/rootfs.tar"
# Image tools run natively on the build machine, even for an ARM64 guest.
"${DOCKER[@]}" run --rm "${CA_ARGS[@]}" -v "$STAGING:/out" "$BASE_IMAGE" sh -ec '
  apk add --no-cache e2fsprogs qemu-img
  mkdir -p /tmp/rootfs
  tar -xf /out/rootfs.tar -C /tmp/rootfs
  truncate -s 8G /out/disk.ext4
  mkfs.ext4 -q -F -L androidagent -d /tmp/rootfs /out/disk.ext4
  qemu-img convert -f raw -O qcow2 /out/disk.ext4 /out/base.qcow2
  gzip -n -1 -c /out/base.qcow2 > /out/base.qcow2.gz
  chmod 644 /out/*
'
fi
cp "$STAGING/base.qcow2.gz" "$STAGING/vmlinuz-virt" "$STAGING/initramfs-virt" "$OUT/"
cp "$STAGING/packages.txt" "docker/guest-packages-$ABI.txt"
python3 tools/guest-package-manifest.py "$STAGING/apk-installed" "docker/guest-packages-$ABI.json"
python3 - "$OUT" <<'PY'
import hashlib,json,sys
from pathlib import Path
p=Path(sys.argv[1]); files=['base.qcow2.gz','vmlinuz-virt','initramfs-virt']
(p/'manifest.json').write_text(json.dumps({'version':1,'disk_gb':8,'files':{f:hashlib.sha256((p/f).read_bytes()).hexdigest() for f in files}},indent=2)+'\n')
PY
python3 tools/prepare-docker-runtime.py "$ABI"
echo "Built Docker guest: $ABI"

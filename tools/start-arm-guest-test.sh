#!/usr/bin/env bash
# Supplementary ARM Linux guest test on an x86 host, not an ARM Android test.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="$PWD/build-runtime/arm-guest-test"
CLOCK_MODE=instruction_count
REUSE_DISK=0
LEGACY_RUNTIME=0
CONTAINERD_DELAY=0
MEMORY=1024
CPU_QUOTA=1
while [ "$#" -gt 0 ]; do
  case "$1" in
    --host-clock) CLOCK_MODE=host; shift;;
    --reuse-disk) REUSE_DISK=1; shift;;
    --legacy-runtime) LEGACY_RUNTIME=1; shift;;
    --containerd-delay) CONTAINERD_DELAY=$2; shift 2;;
    --memory) MEMORY=$2; shift 2;;
    --cpu-quota) CPU_QUOTA=$2; shift 2;;
    *) echo "Unknown ARM guest test argument" >&2; exit 2;;
  esac
done
case "$CONTAINERD_DELAY:$MEMORY" in *[!0-9:]*) exit 2;; esac
if [ "$CONTAINERD_DELAY" -gt 60 ] || [ "$MEMORY" -lt 512 ] || [ "$MEMORY" -gt 2048 ]; then exit 2; fi
mkdir -p "$OUT"
python3 - "$OUT/token" <<'PY'
import secrets,sys
from pathlib import Path
p=Path(sys.argv[1]);p.write_bytes(secrets.token_hex(32).encode()+b'\0'*448);p.chmod(0o600)
PY
DOCKER=(env -u DOCKER_HOST -u DOCKER_CONTEXT -u DOCKER_TLS -u DOCKER_TLS_VERIFY -u DOCKER_CERT_PATH docker --host=unix:///var/run/docker.sock)
CA_ARGS=()
if [ -n "${CODEX_PROXY_CERT:-}" ]; then
  CA_ARGS=(-v /etc/ssl/certs/ca-certificates.crt:/run/proxy-ca.pem:ro -e SSL_CERT_FILE=/run/proxy-ca.pem)
fi
exec "${DOCKER[@]}" run --rm --name androidagent-arm-guest-test \
  --cpus "$CPU_QUOTA" \
  -p 127.0.0.1:19780:7080 -p 127.0.0.1:19781:8080 -p 127.0.0.1:19782:7082 \
  "${CA_ARGS[@]}" -e AA_CLOCK_MODE="$CLOCK_MODE" -e AA_REUSE_DISK="$REUSE_DISK" -e AA_LEGACY_RUNTIME="$LEGACY_RUNTIME" \
  -e AA_CONTAINERD_DELAY="$CONTAINERD_DELAY" -e AA_MEMORY="$MEMORY" \
  -v "$PWD/app/src/main/assets/docker/arm64-v8a:/vm-base:ro" -v "$PWD/build-runtime/guest-arm64-v8a/initramfs-virt:/legacy-initramfs:ro" -v "$OUT:/vm" \
  alpine@sha256:1f3591b8a02ea153f41c5bba878ad477f63ab3d19349762cb77504db02a23e15 sh -ec '
    apk add --no-cache qemu-system-aarch64 qemu-img >/dev/null
    qemu-system-aarch64 --version
    if [ "$AA_REUSE_DISK" = 0 ]; then
      gzip -dc /vm-base/base.qcow2.gz > /vm/base.qcow2
      qemu-img create -f qcow2 -F qcow2 -b /vm/base.qcow2 /vm/user.qcow2
    else
      test -f /vm/base.qcow2 && test -f /vm/user.qcow2
    fi
    INITRAMFS=/vm-base/initramfs-virt
    if [ "$AA_LEGACY_RUNTIME" = 1 ]; then INITRAMFS=/legacy-initramfs; fi
    if [ "$AA_CLOCK_MODE" = host ]; then set -- -accel tcg,thread=multi; else set -- -accel tcg,thread=single -icount shift=3,align=off,sleep=on; fi
    exec qemu-system-aarch64 -machine virt -cpu cortex-a72 "$@" -smp 1 -m "$AA_MEMORY" \
      -kernel /vm-base/vmlinuz-virt -initrd "$INITRAMFS" \
      -append "console=ttyAMA0 root=LABEL=androidagent rootfstype=ext4 rootflags=rw rw init=/opt/androidagent/init.sh modules=virtio_pci,virtio_blk,virtio_net,ext4 aa_containerd_test_delay=$AA_CONTAINERD_DELAY" \
      -drive if=none,file=/vm/user.qcow2,format=qcow2,id=root,cache=writeback -device virtio-blk-device,drive=root \
      -drive if=none,file=/vm/token,format=raw,readonly=on,id=secret -device virtio-blk-device,drive=secret \
      -netdev user,id=net,hostfwd=tcp:0.0.0.0:7080-:7080,hostfwd=tcp:0.0.0.0:8080-:8080 \
      -device virtio-net-device,netdev=net -device qemu-xhci,id=aausb,p2=8,p3=8 -qmp tcp:0.0.0.0:7082,server=on,wait=off -nodefaults -display none -serial stdio -monitor none -no-reboot
  '

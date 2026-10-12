#!/usr/bin/env bash
# Supplementary ARM Linux guest test on an x86 host, not an ARM Android test.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="$PWD/build-runtime/arm-guest-test-044"
CLOCK_MODE=instruction_count
REUSE_DISK=0
LEGACY_RUNTIME=0
CONTAINERD_DELAY=0
DOCKERD_DELAY=0
VCPUS=1
BASELINE_INITRAMFS="$PWD/../tools/usb-runtime/044-baseline-guest/arm64-v8a/initramfs-virt"
REPORT_DIR="$PWD/artifacts/0.4.4/docker-arm"
MEMORY=1024
CPU_QUOTA=1
while [ "$#" -gt 0 ]; do
  case "$1" in
    --host-clock) CLOCK_MODE=host; shift;;
    --reuse-disk) REUSE_DISK=1; shift;;
    --legacy-runtime) LEGACY_RUNTIME=1; shift;;
    --baseline-initramfs) BASELINE_INITRAMFS=$2; shift 2;;
    --report-dir) REPORT_DIR=$2; shift 2;;
    --containerd-delay) CONTAINERD_DELAY=$2; shift 2;;
    --dockerd-delay) DOCKERD_DELAY=$2; shift 2;;
    --vcpus) VCPUS=$2; shift 2;;
    --memory) MEMORY=$2; shift 2;;
    --cpu-quota) CPU_QUOTA=$2; shift 2;;
    *) echo "Unknown ARM guest test argument" >&2; exit 2;;
  esac
done
case "$CONTAINERD_DELAY:$DOCKERD_DELAY:$MEMORY:$VCPUS" in *[!0-9:]*) exit 2;; esac
if [ "$CONTAINERD_DELAY" -gt 60 ] || [ "$DOCKERD_DELAY" -gt 240 ] || [ "$MEMORY" -lt 512 ] || [ "$MEMORY" -gt 2048 ] || [ "$VCPUS" -lt 1 ] || [ "$VCPUS" -gt 2 ]; then exit 2; fi
python3 - "$CPU_QUOTA" <<'PY'
import re,sys
value=sys.argv[1]
if not re.fullmatch(r'[0-9]+(?:\.[0-9]+)?',value) or not .05 <= float(value) <= 8: raise SystemExit(2)
PY
BASELINE_ARGS=()
if [ "$LEGACY_RUNTIME" = 1 ]; then
  test -f "$BASELINE_INITRAMFS"
  BASELINE_ARGS=(-v "$(realpath "$BASELINE_INITRAMFS"):/legacy-initramfs:ro")
fi
mkdir -p "$OUT"
mkdir -p "$REPORT_DIR"
python3 - "$REPORT_DIR/arm-launch-last.json" "$CLOCK_MODE" "$VCPUS" "$MEMORY" "$CPU_QUOTA" "$CONTAINERD_DELAY" "$DOCKERD_DELAY" "$LEGACY_RUNTIME" <<'PY'
import json,sys
from pathlib import Path
destination,clock,vcpus,memory,quota,containerd,dockerd,baseline=sys.argv[1:]
Path(destination).write_text(json.dumps({'clock_mode':clock,'vcpus':int(vcpus),'ram_mb':int(memory),
    'host_cpu_quota':float(quota),'injected_containerd_delay_seconds':int(containerd),
    'injected_dockerd_delay_seconds':int(dockerd),'baseline_runtime':baseline=='1'},indent=2)+'\n')
PY
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
  -e AA_CONTAINERD_DELAY="$CONTAINERD_DELAY" -e AA_DOCKERD_DELAY="$DOCKERD_DELAY" -e AA_VCPUS="$VCPUS" -e AA_MEMORY="$MEMORY" \
  -v "$PWD/app/src/main/assets/docker/arm64-v8a:/vm-base:ro" "${BASELINE_ARGS[@]}" -v "$OUT:/vm" \
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
    exec qemu-system-aarch64 -machine virt -cpu cortex-a72 "$@" -smp "$AA_VCPUS" -m "$AA_MEMORY" -rtc base=utc,clock=host \
      -kernel /vm-base/vmlinuz-virt -initrd "$INITRAMFS" \
      -append "console=ttyAMA0 root=LABEL=androidagent rootfstype=ext4 rootflags=rw rw init=/opt/androidagent/init.sh modules=virtio_pci,virtio_blk,virtio_net,ext4 aa_containerd_test_delay=$AA_CONTAINERD_DELAY aa_dockerd_test_delay=$AA_DOCKERD_DELAY" \
      -drive if=none,file=/vm/user.qcow2,format=qcow2,id=root,cache=writeback -device virtio-blk-device,drive=root \
      -drive if=none,file=/vm/token,format=raw,readonly=on,id=secret -device virtio-blk-device,drive=secret \
      -netdev user,id=net,hostfwd=tcp:0.0.0.0:7080-:7080,hostfwd=tcp:0.0.0.0:8080-:8080 \
      -device virtio-net-device,netdev=net -device qemu-xhci,id=aausb,p2=8,p3=8 -qmp tcp:0.0.0.0:7082,server=on,wait=off -nodefaults -display none -serial stdio -monitor none -no-reboot
  '

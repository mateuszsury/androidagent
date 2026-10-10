#!/bin/sh
# Runs on native Alpine. Cross-installs ARM64 packages without executing ARM code.
set -eu
apk add --no-cache e2fsprogs qemu-img mkinitfs kmod
ROOTFS=/tmp/rootfs
mkdir -p "$ROOTFS/etc/apk/keys"
cp /out/arm-keys/* "$ROOTFS/etc/apk/keys/"
cp /etc/apk/repositories "$ROOTFS/etc/apk/"
apk --root "$ROOTFS" --arch aarch64 --initdb --no-scripts --no-cache add alpine-base linux-virt docker docker-cli-buildx docker-cli-compose python3 curl ca-certificates
# Busybox's package trigger creates its applet links. Use the native equivalent.
/bin/busybox --install -s "$ROOTFS/bin"
mkdir -p "$ROOTFS/opt/androidagent" "$ROOTFS/var/lib/androidagent/tasks"
cp /input/guest/agent.py "$ROOTFS/opt/androidagent/agent.py"
cp -r /input/guest/python "$ROOTFS/opt/androidagent/python"
chmod -R a+rX "$ROOTFS/opt/androidagent/python"
cp /input/guest/init.sh "$ROOTFS/opt/androidagent/init.sh"
chmod 755 "$ROOTFS/opt/androidagent/init.sh"
cp /out/alpine-base.tar /out/alpine-base-id "$ROOTFS/opt/androidagent/"
KERNEL=$(basename "$ROOTFS"/lib/modules/*)
depmod -b "$ROOTFS" "$KERNEL"
mkinitfs -b "$ROOTFS" -F "base virtio ext4" -o /out/initramfs-virt "$KERNEL"
cp "$ROOTFS/boot/vmlinuz-virt" /out/vmlinuz-virt
apk --root "$ROOTFS" --arch aarch64 info -v > /out/packages.txt
cp "$ROOTFS/lib/apk/db/installed" /out/apk-installed
truncate -s 8G /out/disk.ext4
mkfs.ext4 -q -F -L androidagent -d "$ROOTFS" /out/disk.ext4
qemu-img convert -f raw -O qcow2 /out/disk.ext4 /out/base.qcow2
gzip -n -1 -c /out/base.qcow2 > /out/base.qcow2.gz
find /out -maxdepth 1 -type f -exec chmod 644 {} +

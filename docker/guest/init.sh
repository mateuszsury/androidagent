#!/bin/sh
# Minimal PID 1 in the disposable Linux VM. Android keeps its normal kernel.
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
mount -t proc proc /proc
mount -t sysfs sys /sys
mount -t devtmpfs dev /dev 2>/dev/null || true
mkdir -p /dev/pts /run /sys/fs/cgroup /var/log
mount -t devpts devpts /dev/pts
mount -t tmpfs tmpfs /run
export AA_BOOT_ID=$(cat /proc/sys/kernel/random/boot_id)
export AA_DIAGNOSTICS_DIR="/var/lib/androidagent/diagnostics/$AA_BOOT_ID"
mkdir -p "$AA_DIAGNOSTICS_DIR"
chmod 700 /var/lib/androidagent/diagnostics "$AA_DIAGNOSTICS_DIR"
for log in init engine containerd docker agent base-images; do touch "$AA_DIAGNOSTICS_DIR/$log.log"; done
printf 'ANDROIDAGENT_BOOT %s\n' "$AA_BOOT_ID" >> "$AA_DIAGNOSTICS_DIR/init.log"
date -u >> "$AA_DIAGNOSTICS_DIR/init.log"
cat /proc/uptime >> "$AA_DIAGNOSTICS_DIR/init.log"
mount -t cgroup2 none /sys/fs/cgroup
hostname androidagent-linux
for module in overlay bridge br_netfilter nf_nat iptable_nat ip_tables nf_tables tun usbcore xhci-pci usb-storage; do modprobe "$module" 2>/dev/null || true; done
sysctl -w net.ipv4.ip_forward=1 >/dev/null
ip link set lo up
ip link set eth0 up
udhcpc -i eth0 -q -n -t 8 -T 1
mkdir -p /etc/docker
printf '{"log-driver":"json-file","log-opts":{"max-size":"5m","max-file":"2"},"storage-driver":"overlay2","features":{"containerd-snapshotter":false},"bip":"10.89.0.1/24","default-address-pools":[{"base":"10.90.0.0/16","size":24}]}\n' > /etc/docker/daemon.json
# Never truncate another boot's logs. Legacy /var/log files remain untouched.
python3 /opt/androidagent/engine.py &
# virtio-mmio and virtio-pci enumerate disks in different orders. The secret
# device is exactly one 512-byte sector; never assume that it is always vdb.
for disk in /sys/block/vd*; do
  if [ "$(cat "$disk/size")" = 1 ]; then export AA_TOKEN_DEVICE="/dev/${disk##*/}"; fi
done
python3 /opt/androidagent/agent.py &
echo "ANDROIDAGENT_DOCKER_GUEST_STARTED boot=$AA_BOOT_ID runtime_revision=3"
# Processes stream to the console directly as well as to their bounded files.
# tail -f would lose new data after in-place compaction at the fixed size limit.
wait

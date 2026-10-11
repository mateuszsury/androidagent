#!/bin/sh
# Minimal PID 1 in the disposable Linux VM. Android keeps its normal kernel.
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
mount -t proc proc /proc
mount -t sysfs sys /sys
mount -t devtmpfs dev /dev 2>/dev/null || true
mkdir -p /dev/pts /run /sys/fs/cgroup /var/log
touch /var/log/docker.log /var/log/agent.log /var/log/base-images.log
mount -t devpts devpts /dev/pts
mount -t tmpfs tmpfs /run
mount -t cgroup2 none /sys/fs/cgroup
hostname androidagent-linux
for module in overlay bridge br_netfilter nf_nat iptable_nat ip_tables nf_tables tun usbcore xhci-pci usb-storage; do modprobe "$module" 2>/dev/null || true; done
sysctl -w net.ipv4.ip_forward=1 >/dev/null
ip link set lo up
ip link set eth0 up
udhcpc -i eth0 -q -n -t 8 -T 1
mkdir -p /etc/docker
printf '{"log-driver":"json-file","log-opts":{"max-size":"5m","max-file":"2"},"storage-driver":"overlay2","features":{"containerd-snapshotter":false},"bip":"10.89.0.1/24","default-address-pools":[{"base":"10.90.0.0/16","size":24}]}\n' > /etc/docker/daemon.json
dockerd >/var/log/docker.log 2>&1 &
(
  set -e
  until curl --unix-socket /var/run/docker.sock -fs http://localhost/_ping >/dev/null 2>&1; do sleep 1; done
  if [ ! -f /var/lib/androidagent/images-ready ]; then
    docker load -i /opt/androidagent/alpine-base.tar
    docker tag "$(cat /opt/androidagent/alpine-base-id)" alpine:3.23
    # Persist layer contents before publishing readiness. Android may kill the
    # VM immediately afterwards, before Linux writes its dirty page cache.
    sync
    touch /var/lib/androidagent/images-ready
    sync
  fi
) >/var/log/base-images.log 2>&1 &
# virtio-mmio and virtio-pci enumerate disks in different orders. The secret
# device is exactly one 512-byte sector; never assume that it is always vdb.
for disk in /sys/block/vd*; do
  if [ "$(cat "$disk/size")" = 1 ]; then export AA_TOKEN_DEVICE="/dev/${disk##*/}"; fi
done
python3 /opt/androidagent/agent.py >/var/log/agent.log 2>&1 &
echo ANDROIDAGENT_DOCKER_GUEST_STARTED
# Make failed engine startup visible from the Android diagnostics screen.
tail -f /var/log/docker.log /var/log/agent.log /var/log/base-images.log &
wait

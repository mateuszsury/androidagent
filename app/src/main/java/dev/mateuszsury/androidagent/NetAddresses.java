package dev.mateuszsury.androidagent;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The phone's local IPv4 addresses, so a server bound to 0.0.0.0 can be opened from other devices
 * on the same Wi-Fi, Ethernet or the phone's hotspot. Needs only ACCESS_NETWORK_STATE.
 */
final class NetAddresses {
    static final String WIFI = "wifi", HOTSPOT = "hotspot", ETHERNET = "ethernet", CELLULAR = "cellular",
            VPN = "vpn", OTHER = "other";

    static final class Address {
        final String ip, iface, type;

        Address(String ip, String iface, String type) {
            this.ip = ip;
            this.iface = iface == null ? "" : iface;
            this.type = type;
        }

        /** Reachable from other devices on a local network (not mobile data or a VPN tunnel). */
        boolean lan() {
            return WIFI.equals(type) || HOTSPOT.equals(type) || ETHERNET.equals(type);
        }

        String label() {
            switch (type) {
                case WIFI: return "Wi-Fi";
                case HOTSPOT: return "hotspot";
                case ETHERNET: return "Ethernet";
                case CELLULAR: return "sieć komórkowa";
                case VPN: return "VPN";
                default: return iface.isEmpty() ? "sieć" : iface;
            }
        }
    }

    private NetAddresses() {
    }

    /** All non-loopback IPv4 addresses; LAN ones first (Wi-Fi, hotspot, Ethernet). Never throws. */
    static List<Address> read(Context c) {
        List<Address> found = new ArrayList<>();
        Set<String> seenIps = new HashSet<>(), knownIfaces = new HashSet<>();
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                LinkProperties lp = cm.getLinkProperties(n);
                if (caps == null || lp == null) continue;
                String type = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ? VPN
                        : caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? WIFI
                        : caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? ETHERNET
                        : caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? CELLULAR : OTHER;
                if (lp.getInterfaceName() != null) knownIfaces.add(lp.getInterfaceName());
                for (LinkAddress la : lp.getLinkAddresses()) add(found, seenIps, la.getAddress(), lp.getInterfaceName(), type);
            }
        } catch (RuntimeException ignored) {
            // Connectivity service unavailable (tests, early boot): fall back to interfaces.
        }
        try {
            // Interfaces Android does not report as networks, e.g. the phone's own hotspot.
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || knownIfaces.contains(ni.getName())) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) add(found, seenIps, a, ni.getName(), guess(ni.getName()));
            }
        } catch (Exception ignored) {
            // Interface enumeration may be restricted; ConnectivityManager results remain.
        }
        List<Address> ordered = new ArrayList<>();
        for (String type : new String[]{WIFI, HOTSPOT, ETHERNET, CELLULAR, VPN, OTHER})
            for (Address a : found) if (a.type.equals(type)) ordered.add(a);
        return ordered;
    }

    /** IPv4 addresses other devices on the same local network can reach. */
    static List<Address> lan(List<Address> all) {
        List<Address> out = new ArrayList<>();
        for (Address a : all) if (a.lan()) out.add(a);
        return out;
    }

    /** Whether the active (default) network is Wi-Fi. */
    static boolean onWifi(Context c) {
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (RuntimeException e) {
            return false;
        }
    }

    static JSONArray json(List<Address> all) throws JSONException {
        JSONArray out = new JSONArray();
        for (Address a : all)
            out.put(new JSONObject().put("ip", a.ip).put("interface", a.iface).put("type", a.type).put("lan", a.lan()));
        return out;
    }

    private static void add(List<Address> out, Set<String> seen, InetAddress a, String iface, String type) {
        if (!(a instanceof Inet4Address) || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()) return;
        String ip = a.getHostAddress();
        if (ip == null || !seen.add(ip)) return;
        out.add(new Address(ip, iface, type));
    }

    private static String guess(String name) {
        if (name == null) return OTHER;
        // A Wi-Fi-like interface Android does not list as a network is the hotspot (wlan1, ap0, swlan0).
        if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap"))
            return HOTSPOT;
        if (name.startsWith("eth") || name.startsWith("usb") || name.startsWith("rndis")) return ETHERNET;
        if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp") || name.startsWith("v4-"))
            return CELLULAR;
        if (name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("ipsec")) return VPN;
        return OTHER;
    }
}

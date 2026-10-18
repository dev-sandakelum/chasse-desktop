package com.chasse.desktop;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * LAN address discovery: enumerates network interfaces, keeps RFC1918 IPv4
 * addresses only, and sorts them by preference so the QR link can default to
 * the most useful address (configured prefix first, then 10.*, 172.16-31.*,
 * 192.168.*).
 *
 * <p>Adapters that look virtual (VirtualBox, VMware, Hyper-V, WSL, Docker,
 * VPN/TAP/TUN, …) are detected via {@link #looksVirtual} and always sorted
 * AFTER physical adapters — they typically can't reach a phone (PROBLEM 09
 * note: 172.23.0.1 / 192.168.56.1 are usually WSL/Hyper-V and VirtualBox
 * host-only adapters). They stay selectable in the panel, just de-prioritised
 * and labelled.</p>
 */
public final class LanAddresses {

    /** Substrings (lowercase) that mark an adapter as virtual. */
    private static final String[] VIRTUAL_TOKENS = {
            "virtualbox", "vbox", "vmware", "vmnet", "virtual", "docker", "veth",
            "br-", "virbr", "wsl", "hyper-v", "hyperv", "tap", "tun", "zerotier",
            "tailscale", "hamachi", "openvpn", "wireguard", "vpn", "loopback",
            "anytun", "utun", "awdl", "llw", "bridge100", "vm kernel adapter",
    };

    /** A LAN IPv4 address plus the interface it was found on. */
    public record LanAddress(String address, String iface, String display, boolean virtual) {
    }

    private LanAddresses() {
    }

    /** Scans with the default preferred prefix. */
    public static List<LanAddress> scan() {
        return scan(AppConfig.DEFAULT_PREFERRED_PREFIX);
    }

    /**
     * Enumerates all usable LAN addresses:
     * up, non-loopback interfaces; Inet4Address only; skips 127.* and
     * 169.254.* (link-local); keeps RFC1918 private ranges only
     * (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16).
     *
     * <p>Sort order (stable within groups): physical adapters first, then
     * addresses matching {@code preferredPrefix}, then 10.*, then
     * 172.16-31.*, then 192.168.*; virtual adapters last within each group.</p>
     */
    public static List<LanAddress> scan(String preferredPrefix) {
        List<LanAddress> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                String ifaceName = ni.getName();
                String displayName = ni.getDisplayName();
                boolean virtual = looksVirtual(ifaceName, displayName);
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!(a instanceof Inet4Address)) {
                        continue;
                    }
                    if (a.isLoopbackAddress()) {
                        continue;
                    }
                    byte[] b = a.getAddress();
                    if ((b[0] & 0xFF) == 127) {
                        continue; // loopback
                    }
                    if ((b[0] & 0xFF) == 169 && (b[1] & 0xFF) == 254) {
                        continue; // link-local (unconfigured / APIPA)
                    }
                    if (!isRfc1918(b)) {
                        continue; // public / other ranges: never hand these to a QR code
                    }
                    String display = displayName == null || displayName.isBlank()
                            ? ifaceName : displayName;
                    out.add(new LanAddress(a.getHostAddress(), ifaceName, display, virtual));
                }
            }
        } catch (SocketException e) {
            // network enumeration can fail transiently; return what we have
        }
        String prefix = preferredPrefix == null ? "" : preferredPrefix;
        out.sort(Comparator.comparing(LanAddress::virtual)
                .thenComparingInt(la -> category(la.address(), prefix)));
        return out;
    }

    /** True when the interface name / display name looks like a virtual adapter. */
    static boolean looksVirtual(String ifaceName, String displayName) {
        String name = (ifaceName == null ? "" : ifaceName).toLowerCase(Locale.ROOT);
        String disp = (displayName == null ? "" : displayName).toLowerCase(Locale.ROOT);
        for (String token : VIRTUAL_TOKENS) {
            if (name.contains(token) || disp.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRfc1918(byte[] b) {
        int o0 = b[0] & 0xFF, o1 = b[1] & 0xFF;
        if (o0 == 10) {
            return true; // 10.0.0.0/8
        }
        if (o0 == 172 && o1 >= 16 && o1 <= 31) {
            return true; // 172.16.0.0/12
        }
        if (o0 == 192 && o1 == 168) {
            return true; // 192.168.0.0/16
        }
        return false;
    }

    private static int category(String address, String preferredPrefix) {
        if (!preferredPrefix.isEmpty() && address.startsWith(preferredPrefix)) {
            return 0;
        }
        if (address.startsWith("10.")) {
            return 1;
        }
        if (address.startsWith("172.")) {
            String[] parts = address.split("\\.");
            int second = Integer.parseInt(parts[1]);
            return (second >= 16 && second <= 31) ? 2 : 9;
        }
        if (address.startsWith("192.168.")) {
            return 3;
        }
        return 9;
    }

    /** First element of the preference sort, or null when no LAN address exists. */
    public static String pickPreferred(List<LanAddress> addresses) {
        return (addresses == null || addresses.isEmpty()) ? null : addresses.get(0).address();
    }

    /**
     * Polls {@link #scan(String)} on a daemon thread and invokes {@code onChange}
     * whenever the SET of address strings changes (the first poll only establishes
     * the baseline). The returned thread is already started.
     */
    public static Thread startWatcher(String preferredPrefix, long intervalMs,
                                      Consumer<List<LanAddress>> onChange) {
        Thread t = new Thread(() -> {
            Set<String> last = null;
            while (true) {
                List<LanAddress> current = scan(preferredPrefix);
                Set<String> set = new LinkedHashSet<>();
                for (LanAddress la : current) {
                    set.add(la.address());
                }
                if (last == null) {
                    last = set; // baseline only
                } else if (!last.equals(set)) {
                    last = set;
                    try {
                        onChange.accept(current);
                    } catch (Exception e) {
                        // watcher must never die on a listener error
                    }
                }
                try {
                    Thread.sleep(intervalMs);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "lan-address-watcher");
        t.setDaemon(true);
        t.start();
        return t;
    }
}

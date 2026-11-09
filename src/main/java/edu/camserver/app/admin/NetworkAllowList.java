package edu.camserver.app.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** IPv4 CIDR allow list. Parsing is strict: dotted quads only, no host names, no IPv6. */
public final class NetworkAllowList {

    private record Network(String cidr, int address, int mask) {
        boolean contains(int ip) {
            return (ip & mask) == address;
        }
    }

    private final List<Network> networks;

    public NetworkAllowList(List<String> cidrs) {
        List<Network> parsed = new ArrayList<>();
        for (String cidr : cidrs) {
            String text = cidr.trim();
            if (text.isEmpty()) continue;
            int slash = text.indexOf('/');
            int bits = slash < 0 ? 32 : Integer.parseInt(text.substring(slash + 1));
            if (bits < 0 || bits > 32) throw new IllegalArgumentException("Invalid prefix length: " + cidr);
            int address = parseIpv4(slash < 0 ? text : text.substring(0, slash))
                    .orElseThrow(() -> new IllegalArgumentException("Not an IPv4 network: " + cidr));
            int mask = bits == 0 ? 0 : -1 << (32 - bits);
            if ((address & mask) != address) throw new IllegalArgumentException("Host bits set in " + cidr);
            parsed.add(new Network(text, address, mask));
        }
        this.networks = List.copyOf(parsed);
    }

    /** The configured network containing {@code ip}, if any. */
    public Optional<String> match(String ip) {
        Optional<Integer> address = parseIpv4(ip);
        if (address.isEmpty()) return Optional.empty();
        return networks.stream().filter(n -> n.contains(address.get())).map(Network::cidr).findFirst();
    }

    public List<String> cidrs() {
        return networks.stream().map(Network::cidr).toList();
    }

    static Optional<Integer> parseIpv4(String text) {
        if (text == null) return Optional.empty();
        String[] parts = text.trim().split("\\.", -1);
        if (parts.length != 4) return Optional.empty();
        int value = 0;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(Character::isDigit)) return Optional.empty();
            int octet = Integer.parseInt(part);
            if (octet > 255) return Optional.empty();
            value = (value << 8) | octet;
        }
        return Optional.of(value);
    }
}

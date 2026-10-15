package dev.omamobile.host;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;

/** Pure routing policy. Android display and mode IDs are connection-local, never persisted. */
final class DisplaySelection {
    record Candidate(int id, String signature, boolean eligible, boolean secure) { }
    enum Reason { AUTOMATIC, SAVED, UNAVAILABLE, AMBIGUOUS, NONE }
    record Result(int id, Reason reason) { }

    static Result choose(List<Candidate> outputs, String savedSignature) {
        var eligible = outputs.stream().filter(Candidate::eligible).collect(java.util.stream.Collectors.toList());
        if (!savedSignature.isEmpty()) {
            var matches = eligible.stream().filter(output -> output.signature().equals(savedSignature)).collect(java.util.stream.Collectors.toList());
            if (matches.isEmpty()) return new Result(-1, Reason.UNAVAILABLE);
            if (matches.size() != 1) return new Result(-1, Reason.AMBIGUOUS);
            return new Result(matches.get(0).id(), Reason.SAVED);
        }
        return eligible.stream().min(Comparator.comparing(Candidate::secure).thenComparingInt(Candidate::id))
                .map(output -> new Result(output.id(), Reason.AUTOMATIC)).orElse(new Result(-1, Reason.NONE));
    }

    static boolean canRemember(List<Candidate> outputs, Candidate output) {
        return output.eligible() && outputs.stream().filter(Candidate::eligible)
                .filter(item -> item.signature().equals(output.signature())).count() == 1;
    }

    // The public Android API supplies no universally available physical serial. Use an explicitly
    // best-effort name/capability signature, ignoring transient IDs, current mode and orientation.
    static String signature(String name, List<String> modes) {
        String material = name.length() + ":" + name + "\n" + String.join("\n", modes.stream().distinct().sorted().collect(java.util.stream.Collectors.toList()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte value : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}

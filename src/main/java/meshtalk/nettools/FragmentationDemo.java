package meshtalk.nettools;

import meshtalk.common.ChecksumUtil;
import meshtalk.common.MeshLogger;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Demonstrates IPv4 fragmentation and reassembly.
 *
 * <p>Fragmentation rules (RFC 791):
 * <ul>
 *   <li>The data portion of each fragment (except possibly the last) must be a multiple of 8 bytes.</li>
 *   <li>The Fragment Offset is expressed in 8-byte (64-bit) blocks.</li>
 *   <li>The More Fragments (MF) flag is set to 1 on all fragments except the last.</li>
 *   <li>Each fragment carries a full IPv4 header (20 bytes).</li>
 * </ul>
 */
public class FragmentationDemo {

    private static final MeshLogger log = new MeshLogger("FragmentationDemo");

    /**
     * Represents a single IPv4 fragment containing an IP header and payload bytes.
     */
    public static class Fragment {
        public final int fragmentIndex;
        public final Ipv4Header header;
        public final byte[] payload;
        public final int offsetBytes;
        public final boolean moreFragments;

        public Fragment(int fragmentIndex, Ipv4Header header, byte[] payload, int offsetBytes, boolean moreFragments) {
            this.fragmentIndex = fragmentIndex;
            this.header = header;
            this.payload = payload;
            this.offsetBytes = offsetBytes;
            this.moreFragments = moreFragments;
        }

        @Override
        public String toString() {
            return String.format("Fragment #%d: TotalLen=%d, ID=%d, Offset=%d (bytes=%d), MF=%b, DataSize=%d",
                    fragmentIndex, header.totalLength, header.identification,
                    header.fragmentOffset, offsetBytes, moreFragments, payload.length);
        }
    }

    /**
     * Fragment an original packet into multiple fragments based on Path MTU.
     *
     * @param originalHeader the base IPv4 header
     * @param payload        the original payload
     * @param mtu            maximum transmission unit (must be >= 68 per RFC 791, typically >= 28 for 20-byte IP + 8-byte data)
     * @return ordered list of fragments
     */
    public static List<Fragment> fragment(Ipv4Header originalHeader, byte[] payload, int mtu) {
        if (mtu < 28) {
            throw new IllegalArgumentException("MTU must be at least 28 bytes (20 header + 8 data). Given: " + mtu);
        }

        // Check if Don't Fragment (DF) is set (bit 1 of flags)
        if ((originalHeader.flags & 0x02) != 0 && (Ipv4Header.HEADER_SIZE + payload.length) > mtu) {
            throw new IllegalStateException("Packet exceeds MTU and DF (Don't Fragment) flag is set!");
        }

        List<Fragment> fragments = new ArrayList<>();
        int maxDataPerFragment = ((mtu - Ipv4Header.HEADER_SIZE) / 8) * 8; // Multiple of 8 bytes
        if (maxDataPerFragment <= 0) {
            throw new IllegalArgumentException("MTU too small to carry even 8 bytes of payload: " + mtu);
        }

        int totalPayloadBytes = payload.length;
        if (Ipv4Header.HEADER_SIZE + totalPayloadBytes <= mtu) {
            // No fragmentation needed
            Fragment single = new Fragment(0, originalHeader, payload, 0, false);
            fragments.add(single);
            return fragments;
        }

        int currentOffset = 0;
        int fragIndex = 0;

        while (currentOffset < totalPayloadBytes) {
            int remaining = totalPayloadBytes - currentOffset;
            int chunkSize = Math.min(remaining, maxDataPerFragment);
            boolean isLast = (currentOffset + chunkSize >= totalPayloadBytes);

            byte[] chunkData = Arrays.copyOfRange(payload, currentOffset, currentOffset + chunkSize);

            int flagVal = originalHeader.flags & 0x02; // preserve DF if any (usually 0 if fragmenting)
            if (!isLast) {
                flagVal |= 0x01; // MF flag is bit 0 in standard 3-bit notation (or bit 2 depending on indexing)
            }

            int offsetUnits = currentOffset / 8; // in 8-byte units
            int totalLen = Ipv4Header.HEADER_SIZE + chunkData.length;

            Ipv4Header fragHeader = new Ipv4Header(
                    originalHeader.tos,
                    totalLen,
                    originalHeader.identification,
                    flagVal,
                    offsetUnits,
                    originalHeader.ttl,
                    originalHeader.protocol,
                    SubnetCalculator.intToIp(originalHeader.sourceIp),
                    SubnetCalculator.intToIp(originalHeader.destIp)
            );

            fragments.add(new Fragment(fragIndex++, fragHeader, chunkData, currentOffset, !isLast));
            currentOffset += chunkSize;
        }

        return fragments;
    }

    /**
     * Fragment across a multi-hop path where each hop has a different MTU.
     */
    public static List<Fragment> multiHopFragment(Ipv4Header initialHeader, byte[] payload, int[] hopMtus) {
        List<Fragment> currentFragments = new ArrayList<>();
        currentFragments.add(new Fragment(0, initialHeader, payload, 0, false));

        for (int hop = 0; hop < hopMtus.length; hop++) {
            int mtu = hopMtus[hop];
            List<Fragment> nextHopFragments = new ArrayList<>();
            for (Fragment f : currentFragments) {
                if (f.header.totalLength > mtu) {
                    List<Fragment> subFrags = fragment(f.header, f.payload, mtu);
                    // Adjust sub-fragment offsets relative to original payload
                    for (Fragment sf : subFrags) {
                        int absoluteOffset = f.offsetBytes + sf.offsetBytes;
                        Ipv4Header adjustedHeader = new Ipv4Header(
                                sf.header.tos,
                                sf.header.totalLength,
                                sf.header.identification,
                                sf.header.flags | (f.moreFragments ? 0x01 : 0),
                                absoluteOffset / 8,
                                sf.header.ttl,
                                sf.header.protocol,
                                SubnetCalculator.intToIp(sf.header.sourceIp),
                                SubnetCalculator.intToIp(sf.header.destIp)
                        );
                        nextHopFragments.add(new Fragment(
                                nextHopFragments.size(), adjustedHeader, sf.payload,
                                absoluteOffset, sf.moreFragments || f.moreFragments
                        ));
                    }
                } else {
                    nextHopFragments.add(f);
                }
            }
            currentFragments = nextHopFragments;
        }
        return currentFragments;
    }

    /**
     * Reassemble fragments back into the original complete payload.
     * Can handle out-of-order arrival.
     *
     * @param fragments the list of fragments (can be unsorted)
     * @return the reassembled payload bytes
     */
    public static byte[] reassemble(List<Fragment> fragments) {
        if (fragments == null || fragments.isEmpty()) {
            return new byte[0];
        }

        // Sort by offset
        List<Fragment> sorted = new ArrayList<>(fragments);
        sorted.sort(Comparator.comparingInt(f -> f.offsetBytes));

        // Calculate total expected size
        Fragment lastFrag = null;
        for (Fragment f : sorted) {
            if (!f.moreFragments) {
                lastFrag = f;
                break;
            }
        }

        int totalSize;
        if (lastFrag != null) {
            totalSize = lastFrag.offsetBytes + lastFrag.payload.length;
        } else {
            // Fallback if MF wasn't cleared on last fragment
            Fragment maxOffsetFrag = sorted.get(sorted.size() - 1);
            totalSize = maxOffsetFrag.offsetBytes + maxOffsetFrag.payload.length;
        }

        byte[] reassembled = new byte[totalSize];
        for (Fragment f : sorted) {
            System.arraycopy(f.payload, 0, reassembled, f.offsetBytes, f.payload.length);
        }

        return reassembled;
    }
}

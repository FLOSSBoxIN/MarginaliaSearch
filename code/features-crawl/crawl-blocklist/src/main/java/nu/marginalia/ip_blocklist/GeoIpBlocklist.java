package nu.marginalia.ip_blocklist;

import com.google.inject.Singleton;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import lombok.AllArgsConstructor;
import nu.marginalia.WmsaHome;
import nu.marginalia.model.EdgeDomain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Set;
import java.util.TreeMap;

@Singleton
public class GeoIpBlocklist {
    private final TreeMap<Long, GeoIpBlocklist.IpRange> ranges = new TreeMap<>();

    private final Set<String> blacklist = Set.of("CN", "HK");
    private final Set<String> graylist = Set.of("RU", "TW", "IN", "ZA", "SG", "UA");

    private static final Logger logger = LoggerFactory.getLogger(GeoIpBlocklist.class);

    @AllArgsConstructor
    static class IpRange {
        public final long from;
        public final long to;
        public final String country;
    }

    public GeoIpBlocklist() throws IOException, CsvValidationException {
        var resource = WmsaHome.getIPLocationDatabse();

        try (var reader = new CSVReader(new FileReader(resource.toFile()))) {
            for (;;) {
                String[] vals = reader.readNext();
                if (vals == null) {
                    break;
                }
                if (!(blacklist.contains(vals[2]) || graylist.contains(vals[2]))) {
                    continue;
                }
                var range = new GeoIpBlocklist.IpRange(Long.parseLong(vals[0]),
                        Long.parseLong(vals[1]),
                        vals[2]);
                ranges.put(range.from, range);
            }
        }

        logger.info("Loaded {} IP ranges", ranges.size());
    }

    public String getCountry(InetAddress address) {
        byte[] bytes = address.getAddress();
        long ival = ((long)bytes[0]&0xFF) << 24 | ((long)bytes[1]&0xFF) << 16 | ((long)bytes[2]&0xFF)<< 8 | ((long)bytes[3]&0xFF);

        Long key = ranges.floorKey(ival);
        if (null == key) {
            return "-";
        }

        var range = ranges.get(key);
        if (ival >= key && ival < range.to) {
            return range.country;
        }

        return "-";
    }

    public boolean isAllowed(EdgeDomain domain) {
        String country = getCountry(domain);

        if (blacklist.contains(country)) {
            return false;
        }
        if (graylist.contains(country)) {
            return "www".equals(domain.subDomain);
        }

        return true;
    }

    public String getCountry(EdgeDomain domain) {
        try {
            return getCountry(InetAddressCache.getAddress(domain));
        }
        catch (Throwable ex) {
            logger.debug("Failed to resolve {}", domain);
            return "-";
        }
    }
}

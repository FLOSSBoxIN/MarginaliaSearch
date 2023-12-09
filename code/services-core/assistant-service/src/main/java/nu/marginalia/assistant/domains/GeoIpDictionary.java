package nu.marginalia.assistant.domains;

import com.opencsv.CSVReader;
import lombok.AllArgsConstructor;
import nu.marginalia.WmsaHome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.net.InetAddress;
import java.util.TreeMap;

public class GeoIpDictionary {
    private volatile TreeMap<Long, IpRange> ranges = null;
    private static final Logger logger = LoggerFactory.getLogger(GeoIpDictionary.class);

    @AllArgsConstructor
    static class IpRange {
        public final long from;
        public final long to;
        public final String country;
    }

    public GeoIpDictionary() {
        Thread.ofPlatform().start(() -> {
            try (var reader = new CSVReader(new FileReader(WmsaHome.getIPLocationDatabse().toFile()))) {
                var dict = new TreeMap<Long, IpRange>();

                for (;;) {
                    String[] vals = reader.readNext();
                    if (vals == null) {
                        break;
                    }
                    var range = new IpRange(Long.parseLong(vals[0]),
                            Long.parseLong(vals[1]),
                            vals[2]);
                    dict.put(range.from, range);
                }
                ranges = dict;
                logger.info("Loaded {} IP ranges", ranges.size());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

    }

    public String getCountry(String ip) {
        try {
            return getCountry(InetAddress.getByName(ip));
        } catch (Exception e) {
            return "";
        }
    }

    public String getCountry(InetAddress address) {
        if (null == ranges) { // not loaded yet or failed to load
            return "";
        }

        byte[] bytes = address.getAddress();
        long ival = ((long)bytes[0]&0xFF) << 24 | ((long)bytes[1]&0xFF) << 16 | ((long)bytes[2]&0xFF)<< 8 | ((long)bytes[3]&0xFF);

        Long key = ranges.floorKey(ival);
        if (null == key) {
            return "";
        }

        var range = ranges.get(key);
        if (ival >= key && ival < range.to) {
            return range.country;
        }

        return "";
    }
}

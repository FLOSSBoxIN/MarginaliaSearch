package nu.marginalia.ping.ssl;

import java.security.cert.TrustAnchor;
import java.time.Duration;
import java.util.Set;

public class RootCerts {
    private static final String MOZILLA_CA_BUNDLE_URL = "https://curl.se/ca/cacert.pem";

    volatile static boolean initialized = false;
    volatile static Set<TrustAnchor> trustAnchors;

    public static Set<TrustAnchor> getTrustAnchors() {
        if (!initialized) {
            try {
                synchronized (RootCerts.class) {
                    while (!initialized) {
                        RootCerts.class.wait(100);
                    }
                }
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("RootCerts initialization interrupted", e);
            }
        }
        return trustAnchors;
    }

    static {
        Thread.ofPlatform()
                .name("RootCertsUpdater")
                .daemon()
                .unstarted(RootCerts::updateTrustAnchors)
                .start();
    }

    private static void updateTrustAnchors() {
        while (true) {
            try {
                trustAnchors = CertificateFetcher.getRootCerts(MOZILLA_CA_BUNDLE_URL);
                synchronized (RootCerts.class) {
                    initialized = true;
                    RootCerts.class.notifyAll(); // Notify any waiting threads
                }
                Thread.sleep(Duration.ofHours(24));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break; // Exit if interrupted
            } catch (Exception e) {
                // Log the exception and continue to retry
                System.err.println("Failed to update trust anchors: " + e.getMessage());
            }
        }
    }

}

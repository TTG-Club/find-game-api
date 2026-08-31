package club.ttg.findgame.security;

import club.ttg.findgame.config.RateLimitProperties;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.valves.RemoteIpValve;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitProxyTest {

    @TempDir
    Path directory;

    @Test
    void ignoresForwardedAddressesFromUntrustedPeers() throws Exception {
        verifyProxy("(?!)", false);
    }

    @Test
    void usesRightmostUntrustedAddressBehindExplicitlyTrustedProxy() throws Exception {
        verifyProxy("127\\.0\\.0\\.1", true);
    }

    @Test
    void acceptsTrustedProxySubnetInCidrNotation() throws Exception {
        verifyProxy("127.0.0.0/8", true);
    }

    private void verifyProxy(String trustedProxyPattern, boolean trustLocalProxy) throws Exception {
        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir(directory.toString());
        tomcat.setPort(0);
        tomcat.getConnector().setProperty("address", "127.0.0.1");
        Context context = tomcat.addContext("", directory.toString());
        Tomcat.addServlet(context, "api", new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) {
                response.setStatus(200);
            }
        });
        context.addServletMappingDecoded("/api/*", "api");

        RateLimitProperties properties = new RateLimitProperties();
        properties.setRequests(1);
        FilterDef definition = new FilterDef();
        definition.setFilterName("rateLimit");
        definition.setFilter(new RateLimitFilter(properties));
        context.addFilterDef(definition);
        FilterMap mapping = new FilterMap();
        mapping.setFilterName("rateLimit");
        mapping.addURLPattern("/*");
        context.addFilterMap(mapping);

        RemoteIpValve valve = new RemoteIpValve();
        valve.setInternalProxies(trustedProxyPattern);
        valve.setRemoteIpHeader("X-Forwarded-For");
        tomcat.getEngine().getPipeline().addValve(valve);
        try {
            tomcat.start();
            URI uri = URI.create("http://127.0.0.1:" + tomcat.getConnector().getLocalPort() + "/api/test");
            assertThat(send(uri, "192.0.2.1")).isEqualTo(200);
            assertThat(send(uri, "192.0.2.2")).isEqualTo(trustLocalProxy ? 200 : 429);
            // Adding attacker-controlled addresses on the left must not reset the client's quota.
            assertThat(send(uri, "198.51.100.99, 192.0.2.1")).isEqualTo(429);
            URI encodedUri = URI.create(uri.toString().replace("/api/", "/%61pi/"));
            assertThat(send(encodedUri, "192.0.2.1")).isEqualTo(429);
        } finally {
            tomcat.stop();
            tomcat.destroy();
        }
    }

    private int send(URI uri, String forwardedFor) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection(Proxy.NO_PROXY);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestProperty("X-Forwarded-For", forwardedFor);
        try {
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }
}

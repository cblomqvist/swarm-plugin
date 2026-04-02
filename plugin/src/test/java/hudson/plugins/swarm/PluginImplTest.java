package hudson.plugins.swarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import hudson.model.Node;
import hudson.slaves.NodeProperty;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import jenkins.slaves.JnlpAgentReceiver;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

public class PluginImplTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    /*
     * Regression coverage for reconnect handling in PluginImpl#doCreateSlave.
     *
     * Historically PluginImpl had no direct tests; behavior was covered indirectly via
     * Swarm client integration tests. The reconnect fix now mutates node properties around
     * disconnect/reconnect timing, so we add focused tests here to lock down that logic:
     *  - A temporary KeepSwarmClientNodeProperty property is removed when we added it only for reconnect
     *  - A pre-existing KeepSwarmClientNodeProperty property remains intact
     */

    @Test
    public void doCreateSlaveTemporarilyAddsAndRemovesKeepDisconnectedClientsProperty() throws Exception {
        // Arrange: existing node without KeepSwarmClientNodeProperty.
        SwarmSlave node = createSwarmNode("agent-one-hash", false);
        String remoteFsRoot = j.createTmpDir().toString();

        // Act: same logical agent reconnects (name + hash), keeping existing node.
        PluginImpl plugin = new PluginImpl();
        RecordingStaplerResponse response = new RecordingStaplerResponse();
        plugin.doCreateSlave(
                request(),
                response.proxy(),
                "agent-one",
                null,
                1,
                remoteFsRoot,
                "",
                Node.Mode.NORMAL,
                "hash",
                false,
                false);

        // Assert: node was preserved and temporary KeepSwarmClientNodeProperty cleanup happened.
        Node preservedNode = j.jenkins.getNode(node.getNodeName());
        assertNotNull(preservedNode);
        assertNull(preservedNode.getNodeProperty(KeepSwarmClientNodeProperty.class));

        // The endpoint still returns reconnect credentials for the preserved node.
        Properties properties = response.asProperties();
        assertEquals(node.getNodeName(), properties.getProperty("name"));
        assertEquals(JnlpAgentReceiver.SLAVE_SECRET.mac(node.getNodeName()), properties.getProperty("secret"));
    }

    @Test
    public void doCreateSlaveKeepsExistingKeepDisconnectedClientsProperty() throws Exception {
        // Arrange: existing node already configured to be kept when disconnected.
        SwarmSlave node = createSwarmNode("agent-two-hash", true);
        String remoteFsRoot = j.createTmpDir().toString();

        // Act: reconnect for the same node identity.
        PluginImpl plugin = new PluginImpl();
        RecordingStaplerResponse response = new RecordingStaplerResponse();
        plugin.doCreateSlave(
                request(),
                response.proxy(),
                "agent-two",
                null,
                1,
                remoteFsRoot,
                "",
                Node.Mode.NORMAL,
                "hash",
                false,
                false);

        // Assert: existing KeepSwarmClientNodeProperty configuration is preserved.
        Node preservedNode = j.jenkins.getNode(node.getNodeName());
        assertNotNull(preservedNode);
        assertNotNull(preservedNode.getNodeProperty(KeepSwarmClientNodeProperty.class));

        // Reconnect credentials are still returned for the same node.
        Properties properties = response.asProperties();
        assertEquals(node.getNodeName(), properties.getProperty("name"));
        assertEquals(JnlpAgentReceiver.SLAVE_SECRET.mac(node.getNodeName()), properties.getProperty("secret"));
    }

    private SwarmSlave createSwarmNode(String nodeName, boolean keepDisconnectedClients) throws Exception {
        List<NodeProperty<Node>> nodeProperties = new ArrayList<>();
        if (keepDisconnectedClients) {
            nodeProperties.add(new KeepSwarmClientNodeProperty());
        }

        SwarmSlave node = new SwarmSlave(
                nodeName,
                "test swarm node",
                j.createTmpDir().toString(),
                "1",
                Node.Mode.NORMAL,
                "swarm",
                nodeProperties);
        j.jenkins.addNode(node);
        return node;
    }

    private StaplerRequest2 request() {
        return (StaplerRequest2) Proxy.newProxyInstance(
                StaplerRequest2.class.getClassLoader(),
                new Class<?>[] {StaplerRequest2.class},
                (proxy, method, args) -> {
                    String methodName = method.getName();
                    if ("getParameterValues".equals(methodName)) {
                        return null;
                    }
                    if ("getRemoteHost".equals(methodName)) {
                        return "127.0.0.1";
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }

    private static final class RecordingStaplerResponse {

        private final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        private final StringWriter writer = new StringWriter();

        private final StaplerResponse2 proxy = (StaplerResponse2) Proxy.newProxyInstance(
                StaplerResponse2.class.getClassLoader(),
                new Class<?>[] {StaplerResponse2.class},
                (ignored, method, args) -> {
                    String methodName = method.getName();
                    if ("getOutputStream".equals(methodName)) {
                        return new RecordingServletOutputStream(outputStream);
                    }
                    if ("getWriter".equals(methodName)) {
                        return new PrintWriter(writer, true);
                    }
                    return defaultValue(method.getReturnType());
                });

        private StaplerResponse2 proxy() {
            return proxy;
        }

        private Properties asProperties() throws IOException {
            Properties properties = new Properties();
            properties.load(new java.io.ByteArrayInputStream(outputStream.toByteArray()));
            return properties;
        }
    }

    private static final class RecordingServletOutputStream extends ServletOutputStream {

        private final ByteArrayOutputStream outputStream;

        private RecordingServletOutputStream(ByteArrayOutputStream outputStream) {
            this.outputStream = outputStream;
        }

        @Override
        public void write(int b) {
            outputStream.write(b);
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {
            // no-op for tests
        }
    }
}

package hudson.plugins.swarm;

import hudson.Functions;
import hudson.Plugin;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Descriptor.FormException;
import hudson.model.Node;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.slaves.NodeProperty;
import hudson.slaves.OfflineCause;
import hudson.tools.ToolDescriptor;
import hudson.tools.ToolInstallation;
import hudson.tools.ToolLocationNodeProperty;
import hudson.tools.ToolLocationNodeProperty.ToolLocation;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.slaves.JnlpAgentReceiver;
import org.apache.commons.lang.ArrayUtils;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.verb.POST;

/**
 * Exposes an entry point to add a new Swarm agent.
 *
 * @author Kohsuke Kawaguchi
 */
public class PluginImpl extends Plugin {

    private static final Logger LOGGER = Logger.getLogger(PluginImpl.class.getName());

    private Node getNodeByName(String name, StaplerResponse2 rsp) throws IOException {
        Jenkins jenkins = Jenkins.get();
        Node node = jenkins.getNode(name);

        if (node == null) {
            rsp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            rsp.setContentType("text/plain; UTF-8");
            rsp.getWriter().printf("Agent \"%s\" does not exist.%n", name);
            return null;
        }

        return node;
    }

    /** Get the list of labels for an agent. */
    @SuppressWarnings({"lgtm[jenkins/csrf]", "lgtm[jenkins/no-permission-check]"})
    public void doGetSlaveLabels(StaplerRequest2 req, StaplerResponse2 rsp, @QueryParameter String name)
            throws IOException {
        Node node = getNodeByName(name, rsp);
        if (node == null) {
            return;
        }

        normalResponse(req, rsp, node.getLabelString());
    }

    private void normalResponse(StaplerRequest2 req, StaplerResponse2 rsp, String sLabelList) throws IOException {
        rsp.setContentType("text/xml");

        try (Writer writer = rsp.getWriter()) {
            writer.write("<labelResponse><labels>" + sLabelList + "</labels></labelResponse>");
        }
    }

    /** Add labels to an agent. */
    @POST
    public void doAddSlaveLabels(
            StaplerRequest2 req, StaplerResponse2 rsp, @QueryParameter String name, @QueryParameter String labels)
            throws IOException {
        Node node = getNodeByName(name, rsp);
        if (node == null) {
            return;
        }

        node.checkPermission(Computer.CONFIGURE);

        LinkedHashSet<String> currentLabels = stringToSet(node.getLabelString());
        LinkedHashSet<String> labelsToAdd = stringToSet(labels);
        currentLabels.addAll(labelsToAdd);
        node.setLabelString(setToString(currentLabels));

        normalResponse(req, rsp, node.getLabelString());
    }

    private static String setToString(Set<String> labels) {
        return String.join(" ", labels);
    }

    private static LinkedHashSet<String> stringToSet(String labels) {
        return new LinkedHashSet<>(List.of(labels.split("\\s+")));
    }

    private static boolean hasKeepDisconnectedClientsProperty(Node node) {
        return node.getNodeProperty(KeepSwarmClientNodeProperty.class) != null;
    }

    private static void addKeepDisconnectedClientsProperty(Node node) throws IOException {
        node.getNodeProperties().add(new KeepSwarmClientNodeProperty());
        node.save();
    }

    private static void removeKeepDisconnectedClientsProperty(Node node) throws IOException {
        KeepSwarmClientNodeProperty keepClientProp = node.getNodeProperty(KeepSwarmClientNodeProperty.class);
        if (keepClientProp != null) {
            node.getNodeProperties().remove(keepClientProp);
            node.save();
        }
    }

    /** Remove labels from an agent. */
    @POST
    public void doRemoveSlaveLabels(
            StaplerRequest2 req, StaplerResponse2 rsp, @QueryParameter String name, @QueryParameter String labels)
            throws IOException {
        Node node = getNodeByName(name, rsp);
        if (node == null) {
            return;
        }

        node.checkPermission(Computer.CONFIGURE);

        LinkedHashSet<String> currentLabels = stringToSet(node.getLabelString());
        LinkedHashSet<String> labelsToRemove = stringToSet(labels);
        currentLabels.removeAll(labelsToRemove);
        node.setLabelString(setToString(currentLabels));

        normalResponse(req, rsp, node.getLabelString());
    }

    /** Add a new Swarm agent. */
    @POST
    public void doCreateSlave(
            StaplerRequest2 req,
            StaplerResponse2 rsp,
            @QueryParameter String name,
            @QueryParameter(fixEmpty = true) String description,
            @QueryParameter int executors,
            @QueryParameter String remoteFsRoot,
            @QueryParameter String labels,
            @QueryParameter Node.Mode mode,
            @QueryParameter(fixEmpty = true) String hash,
            @QueryParameter boolean deleteExistingClients,
            @QueryParameter boolean keepDisconnectedClients)
            throws IOException, InterruptedException {
        Jenkins jenkins = Jenkins.get();

        jenkins.checkPermission(Computer.CREATE);
        jenkins.checkPermission(Computer.CONNECT);

        List<NodeProperty<Node>> nodeProperties = new ArrayList<>();

        String[] toolLocations = req.getParameterValues("toolLocation");
        if (!ArrayUtils.isEmpty(toolLocations)) {
            List<ToolLocation> parsedToolLocations = parseToolLocations(toolLocations);
            nodeProperties.add(new ToolLocationNodeProperty(parsedToolLocations));
        }

        String[] environmentVariables = req.getParameterValues("environmentVariable");
        if (!ArrayUtils.isEmpty(environmentVariables)) {
            List<EnvironmentVariablesNodeProperty.Entry> parsedEnvironmentVariables =
                    parseEnvironmentVariables(environmentVariables);
            nodeProperties.add(new EnvironmentVariablesNodeProperty(parsedEnvironmentVariables));
        }

        // We use the existance of the node property itself as the boolean flag
        if (keepDisconnectedClients) {
            nodeProperties.add(new KeepSwarmClientNodeProperty());
        }

        if (hash == null && jenkins.getNode(name) != null && !deleteExistingClients) {
            /*
             * This is a legacy client. They won't be able to pick up the new name, so throw them
             * away. Perhaps they can find another controller to connect to.
             */
            rsp.setStatus(HttpServletResponse.SC_CONFLICT);
            rsp.setContentType("text/plain; UTF-8");
            rsp.getWriter().printf("Agent \"%s\" already exists.%n", name);
            return;
        }

        if (hash != null) {
            /*
             * Try to make the name unique. Swarm clients are often replicated VMs, and they may
             * have the same name.
             */
            name = name + '-' + hash;
        }

        // Check for existing connections.
        Node node = jenkins.getNode(name);
        if (node != null) {
            /*
             * The node already exists. The behaviour depends on deleteExistingClients:
             *
             *  - false (same-host reconnection): preserve the existing node so that
             *    running builds survive. Disconnect any stale channel/WebSocket session
             *    so that WebSocketAgents#doIndex no longer rejects the new connection
             *    with "already connected", then return the existing node's secret.
             *
             *  - true  (agent replacement): disconnect the stale channel, then remove
             *    the old node and fall through to create a brand-new SwarmSlave.
             *
             * computer.disconnect() returns a Future; we wait synchronously (up to 15 s)
             * for it to complete, matching the pattern used by
             * DefaultJnlpSlaveReceiver#afterProperties.
             */
            boolean addedKeepDisconnectedClientsPropertyForReconnect = false;
            boolean hadKeepDisconnectedClientsProperty = hasKeepDisconnectedClientsProperty(node);
            Computer computer = node.toComputer();
            if (!deleteExistingClients && !hadKeepDisconnectedClientsProperty) {
                LOGGER.log(
                        Level.INFO,
                        "Temporarily marking agent \"{0}\" to be kept across disconnect so the existing node can be reused.",
                        name);
                addKeepDisconnectedClientsProperty(node);
                addedKeepDisconnectedClientsPropertyForReconnect = true;
            }
            if (computer != null && computer.isOnline()) {
                LOGGER.log(Level.INFO, "Disconnecting stale channel for agent \"{0}\" to allow reconnection.", name);
                try {
                    computer.disconnect(new OfflineCause() {
                                @Override
                                public String toString() {
                                    return "Swarm client is reconnecting";
                                }
                            })
                            .get(15, TimeUnit.SECONDS);
                } catch (ExecutionException | TimeoutException e) {
                    LOGGER.log(
                            Level.WARNING,
                            "Timed out waiting for agent \"" + name + "\" to go offline. "
                                    + "The new connection may be rejected by WebSocketAgents.",
                            e);
                }
            }

            if (addedKeepDisconnectedClientsPropertyForReconnect) {
                if (computer == null || computer.getChannel() == null) {
                    LOGGER.log(
                            Level.INFO,
                            "Removing temporarily KeepSwarmClientNodeProperty for agent \"{0}\" to preserve desired behavior.",
                            name);
                    removeKeepDisconnectedClientsProperty(node);
                } else {
                    LOGGER.log(
                            Level.WARNING,
                            "Agent \"{0}\" is still connected after the reconnect disconnect attempt. "
                                    + "Leaving KeepSwarmClientNodeProperty in place to avoid node removal.",
                            name);
                }
            }

            if (!deleteExistingClients) {
                // Preserve the existing node — return its secret so the client
                // reconnects to the same Computer and running builds to resume.
                LOGGER.log(
                        Level.INFO,
                        "Option '-deleteExistingClients' not set. Preserving existing node for agent \"{0}\" to allow running builds to resume.",
                        name);
                rsp.setContentType("text/plain; charset=iso-8859-1");
                try (OutputStream outputStream = rsp.getOutputStream()) {
                    Properties props = new Properties();
                    props.put("name", name);
                    props.put("secret", JnlpAgentReceiver.SLAVE_SECRET.mac(name));
                    props.store(outputStream, "");
                }
                return;
            }

            // deleteExistingClients — remove the old node, then fall through to
            // create a fresh SwarmSlave below.
            jenkins.removeNode(node);
        }

        try {
            String nodeDescription = "Swarm agent from " + req.getRemoteHost();
            if (description != null) {
                nodeDescription += ": " + description;
            }
            LOGGER.log(
                    Level.INFO, "Setting up new agent: \"{0}\", possibly replacing existing agent.", nodeDescription);
            SwarmSlave agent = new SwarmSlave(
                    name,
                    nodeDescription,
                    remoteFsRoot,
                    String.valueOf(executors),
                    mode,
                    "swarm " + Util.fixNull(labels),
                    nodeProperties);
            jenkins.addNode(agent);

            rsp.setContentType("text/plain; charset=iso-8859-1");
            try (OutputStream outputStream = rsp.getOutputStream()) {
                Properties props = new Properties();
                props.put("name", name);
                props.put("secret", JnlpAgentReceiver.SLAVE_SECRET.mac(name));
                props.store(outputStream, "");
            }
        } catch (FormException e) {
            Functions.printStackTrace(e, System.err);
        }
    }

    private static List<ToolLocation> parseToolLocations(String[] toolLocations) {
        List<ToolLocationNodeProperty.ToolLocation> result = new ArrayList<>();

        for (String toolLocKeyValue : toolLocations) {
            boolean found = false;
            /*
             * Limit the split on only the first occurrence of ':' so that the tool location path
             * can contain ':' characters.
             */
            String[] toolLoc = toolLocKeyValue.split(":", 2);

            for (ToolDescriptor<?> desc : ToolInstallation.all()) {
                for (ToolInstallation inst : desc.getInstallations()) {
                    if (inst.getName().equals(toolLoc[0])) {
                        found = true;

                        String location = toolLoc[1];

                        ToolLocationNodeProperty.ToolLocation toolLocation =
                                new ToolLocationNodeProperty.ToolLocation(desc, inst.getName(), location);
                        result.add(toolLocation);
                    }
                }
            }

            // Don't fail silently; rather, inform the user what tool is missing.
            if (!found) {
                throw new RuntimeException("No tool '" + toolLoc[0] + "' is defined on Jenkins.");
            }
        }

        return result;
    }

    private static List<EnvironmentVariablesNodeProperty.Entry> parseEnvironmentVariables(
            String[] environmentVariables) {
        List<EnvironmentVariablesNodeProperty.Entry> result = new ArrayList<>();

        for (String environmentVariable : environmentVariables) {
            /*
             * Limit the split on only the first occurrence of ':' so that the value can contain ':'
             * characters.
             */
            String[] keyValue = environmentVariable.split(":", 2);
            EnvironmentVariablesNodeProperty.Entry var =
                    new EnvironmentVariablesNodeProperty.Entry(keyValue[0], keyValue[1]);
            result.add(var);
        }

        return result;
    }
}

package hudson.plugins.swarm;

import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.spi.MapOptionHandler;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Options {

    @Option(name = "-name", usage = "Name of the agent.")
    public String name;

    @Option(name = "-description", usage = "Description to be put on the agent.")
    public String description;

    @Option(
            name = "-labels",
            usage =
                    "Whitespace-separated list of labels to be assigned for this agent. Multiple"
                            + " options are allowed.")
    public List<String> labels = new ArrayList<>();

    @Option(name = "-fsroot", usage = "Remote root directory.")
    public File fsroot = new File(".");

    @Option(name = "-executors", usage = "Number of executors")
    public int executors = Runtime.getRuntime().availableProcessors();

    @Option(
            name = "-url",
            aliases = "-master",
            usage = "The complete target Jenkins URL like 'http://server:8080/jenkins/'.")
    public String url;

    @Option(
            name = "-tunnel",
            usage =
                    "Connect to the specified host and port, instead of connecting directly to"
                        + " Jenkins. Useful when connection to Jenkins needs to be tunneled. Can be"
                        + " also HOST: or :PORT, in which case the missing portion will be"
                        + " auto-configured like the default behavior")
    public String tunnel;

    @Option(
            name = "-webSocket",
            usage = "Connect using the WebSocket protocol.",
            forbids = "-tunnel")
    public boolean webSocket;

    @Option(
            name = "-webSocketHeader",
            usage =
                    "Additional WebSocket header to set, e.g. for authenticating with reverse"
                        + " proxies. To specify multiple headers, call this flag multiple times,"
                        + " one with each header.",
            metaVar = "NAME=VALUE",
            depends = "-webSocket")
    public Map<String, String> webSocketHeaders;

    @Option(
            name = "-noRetryAfterConnected",
            usage = "Do not retry if a successful connection gets closed.")
    public boolean noRetryAfterConnected;

    @Option(
            name = "-retry",
            usage = "Number of retries before giving up. Unlimited if not specified.")
    public int retry = -1;

    @Option(
            name = "-retryBackOffStrategy",
            usage =
                    "The mode controlling retry wait time. Can be either 'none' (use same interval"
                        + " between retries) or 'linear' (increase wait time before each retry up"
                        + " to maxRetryInterval) or 'exponential' (double wait interval on each"
                        + " retry up to maxRetryInterval). Default is 'none'.",
            handler = RetryBackOffStrategyOptionHandler.class)
    public RetryBackOffStrategy retryBackOffStrategy = RetryBackOffStrategy.NONE;

    @Option(
            name = "-retryInterval",
            usage = "Time to wait before retry in seconds. Default is 10 seconds.")
    public int retryInterval = 10;

    @Option(
            name = "-maxRetryInterval",
            usage = "Max time to wait before retry in seconds. Default is 60 seconds.")
    public int maxRetryInterval = 60;

    @Option(
            name = "-disableSslVerification",
            usage = "Disable SSL verification in the HTTP client.")
    public boolean disableSslVerification;

    @Option(
            name = "-sslFingerprints",
            usage =
                    "Whitespace-separated list of accepted certificate fingerprints (SHA-256/Hex), "
                            + "otherwise system truststore will be used. "
                            + "No revocation, expiration or not yet valid check will be performed "
                            + "for custom fingerprints! Multiple options are allowed.")
    public String sslFingerprints = "";

    @Option(name = "-disableClientsUniqueId", usage = "Disable client's unique ID.")
    public boolean disableClientsUniqueId;

    @Option(
            name = "-deleteExistingClients",
            usage = "Delete any existing agent with the same name.")
    public boolean deleteExistingClients;

    @Option(
            name = "-keepDisconnectedClients",
            usage = "Do not remove clients from the controller when the agent becomes disconnected.")
    public boolean keepDisconnectedClients;

    @Option(
            name = "-mode",
            usage =
                    "The mode controlling how Jenkins allocates jobs to agents. Can be either '"
                            + ModeOptionHandler.NORMAL
                            + "' (use this node as much as possible) or '"
                            + ModeOptionHandler.EXCLUSIVE
                            + "' (only build jobs with label expressions matching this node)."
                            + " Default is '"
                            + ModeOptionHandler.NORMAL
                            + "'.",
            handler = ModeOptionHandler.class)
    public String mode = ModeOptionHandler.NORMAL;

    @Option(
            name = "-t",
            aliases = "--toolLocation",
            usage =
                    "A tool location to be defined on this agent. It is specified as"
                            + " 'toolName=location'.",
            handler = MapOptionHandler.class)
    public Map<String, String> toolLocations;

    @Option(
            name = "-e",
            aliases = "--env",
            usage =
                    "An environment variable to be defined on this agent. It is specified as"
                            + " 'key=value'. Multiple variables are allowed.",
            handler = MapOptionHandler.class)
    public Map<String, String> environmentVariables;

    @Option(name = "-username", usage = "The Jenkins username for authentication.")
    public String username;

    @Option(name = "-password", usage = "The Jenkins user API token or password.")
    public String password;

    @Option(
            name = "-help",
            aliases = {"--help", "-h"},
            help = true,
            usage = "Show the help screen")
    public boolean help;

    @Option(
            name = "-passwordEnvVariable",
            usage = "Environment variable containing the Jenkins user API token or password.")
    public String passwordEnvVariable;

    @Option(
            name = "-passwordFile",
            usage = "File containing the Jenkins user API token or password.")
    public String passwordFile;

    @Option(
            name = "-labelsFile",
            usage =
                    "File location with space delimited list of labels.  If the file changes, the"
                            + " client is restarted.")
    public String labelsFile;

    @Option(
            name = "-pidFile",
            usage =
                    "File to write PID to. The client will refuse to start if this file exists "
                            + "and the previous process is still running.")
    public String pidFile;

    @Option(
            name = "-disableWorkDir",
            usage = "Disable Remoting working directory support and run the agent in legacy mode.",
            forbids = {"-workDir", "-internalDir", "-failIfWorkDirIsMissing"})
    public boolean disableWorkDir = false;

    @Option(
            name = "-workDir",
            usage = "The Remoting working directory where the JAR cache and logs will be stored.",
            forbids = "-disableWorkDir")
    public File workDir;

    @Option(
            name = "-internalDir",
            usage =
                    "The name of the directory within the Remoting working directory where files"
                            + " internal to Remoting will be stored.",
            forbids = "-disableWorkDir")
    public File internalDir;

    @Option(
            name = "-jar-cache",
            usage = "Cache directory that stores JAR files sent from the controller.")
    public File jarCache;

    @Option(
            name = "-failIfWorkDirIsMissing",
            usage =
                    "Fail if the requested Remoting working directory or internal directory is"
                            + " missing.",
            forbids = "-disableWorkDir")
    public boolean failIfWorkDirIsMissing = false;

    @Option(
            name = "-prometheusPort",
            usage = "If defined, then start an HTTP service on this port for Prometheus metrics.")
    public int prometheusPort = -1;

    @Option(name = "-config", usage = "YAML configuration file containing the options.")
    public File config;
}

package edu.stanford.nlp.pipeline;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import edu.stanford.nlp.io.IOUtils;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.ling.IndexedWord;
import edu.stanford.nlp.ling.tokensregex.SequenceMatchResult;
import edu.stanford.nlp.ling.tokensregex.TokenSequenceMatcher;
import edu.stanford.nlp.ling.tokensregex.TokenSequencePattern;
import edu.stanford.nlp.semgraph.SemanticGraphCoreAnnotations;
import edu.stanford.nlp.semgraph.semgrex.SemgrexMatcher;
import edu.stanford.nlp.semgraph.semgrex.SemgrexPattern;
import edu.stanford.nlp.util.*;

import java.io.*;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static edu.stanford.nlp.util.logging.Redwood.Util.*;

/**
 * This class creates a server that runs a new Java annotator in each thread.
 *
 */
public class StanfordCoreNLPServer implements Runnable {
  protected static int DEFAULT_PORT = 9000;

  protected HttpServer server;
  protected int serverPort;
  protected final FileHandler staticPageHandle;
  protected final String shutdownKey;

  public static int HTTP_OK = 200;
  public static int HTTP_BAD_INPUT = 400;
  public static int HTTP_ERR = 500;
  public final Properties defaultProps;

  /**
   * The thread pool for the HTTP server.
   */
  private final ExecutorService serverExecutor = Executors.newFixedThreadPool(Execution.threads);
  /**
   * To prevent grossly wasteful over-creation of pipeline objects, cache the last
   * few we created, until the garbage collector decides we can kill them.
   */
  private final WeakHashMap<Properties, StanfordCoreNLP> pipelineCache = new WeakHashMap<>();
  /**
   * An executor to time out CoreNLP execution with.
   */
  private final ExecutorService corenlpExecutor = Executors.newFixedThreadPool(Execution.threads);


  public StanfordCoreNLPServer(int port) throws IOException {
    serverPort = port;

    defaultProps = new Properties();
    defaultProps.setProperty("annotators", "tokenize, ssplit, pos, lemma, ner, parse, depparse, natlog, openie, dcoref");
    defaultProps.setProperty("inputFormat", "text");
    defaultProps.setProperty("outputFormat", "json");

    // Generate and write a shutdown key
    String tmpDir = System.getProperty("java.io.tmpdir");
    File tmpFile = new File(tmpDir + File.separator + "corenlp.shutdown");
    tmpFile.deleteOnExit();
    if (tmpFile.exists()) {
      if (!tmpFile.delete()) {
        throw new IllegalStateException("Could not delete shutdown key file");
      }
    }
    this.shutdownKey = new BigInteger(130, new Random()).toString(32);
    IOUtils.writeStringToFile(shutdownKey, tmpFile.getPath(), "utf-8");

    // Set the static page handler
    this.staticPageHandle = new FileHandler("edu/stanford/nlp/pipeline/demo/corenlp-brat.html");
  }

  /**
   * Parse the URL parameters into a map of (key, value) pairs.
   *
   * @param uri The URL that was requested.
   *
   * @return A map of (key, value) pairs corresponding to the request parameters.
   *
   * @throws UnsupportedEncodingException Thrown if we could not decode the URL with utf8.
   */
  private static Map<String, String> getURLParams(URI uri) throws UnsupportedEncodingException {
    if (uri.getQuery() != null) {
      Map<String, String> urlParams = new HashMap<>();

      String query = uri.getQuery();
      String[] queryFields = query
          .replaceAll("\\\\&", "___AMP___")
          .replaceAll("\\\\+", "___PLUS___")
          .split("&");
      for (String queryField : queryFields) {
        int firstEq = queryField.indexOf('=');
        // Convention uses "+" for spaces.
        String key = URLDecoder.decode(queryField.substring(0, firstEq), "utf8").replaceAll("___AMP___", "&").replaceAll("___PLUS___", "+");
        String value = URLDecoder.decode(queryField.substring(firstEq + 1), "utf8").replaceAll("___AMP___", "&").replaceAll("___PLUS___", "+");
        urlParams.put(key, value);
      }
      return urlParams;
    } else {
      return Collections.emptyMap();
    }
  }

  /**
   * Reads the POST contents of the request and parses it into an Annotation object, ready to be annotated.
   * This method can also read a serialized document, if the input format is set to be serialized.
   *
   * @param props The properties we are annotating with. This is where the input format is retrieved from.
   * @param httpExchange The exchange we are reading POST data from.
   *
   * @return An Annotation representing the read document.
   *
   * @throws IOException Thrown if we cannot read the POST data.
   * @throws ClassNotFoundException Thrown if we cannot load the serializer.
   */
  private Annotation getDocument(Properties props, HttpExchange httpExchange) throws IOException, ClassNotFoundException {
    String inputFormat = props.getProperty("inputFormat", "text");
    switch (inputFormat) {
      case "text":
        return new Annotation(IOUtils.slurpReader(new InputStreamReader(httpExchange.getRequestBody())));
      case "serialized":
        String inputSerializerName = props.getProperty("inputSerializer", ProtobufAnnotationSerializer.class.getName());
        AnnotationSerializer serializer = MetaClass.create(inputSerializerName).createInstance();
        Pair<Annotation, InputStream> pair = serializer.read(httpExchange.getRequestBody());
        return pair.first;
      default:
        throw new IOException("Could not parse input format: " + inputFormat);
    }
  }


  /**
   * Create (or retrieve) a StanfordCoreNLP object corresponding to these properties.
   * @param props The properties to create the object with.
   * @return A pipeline parameterized by these properties.
   */
  private StanfordCoreNLP mkStanfordCoreNLP(Properties props) {
    StanfordCoreNLP impl;
    synchronized (pipelineCache) {
      impl = pipelineCache.get(props);
      if (impl == null) {
        impl = new StanfordCoreNLP(props);
        pipelineCache.put(props, impl);
      }
    }
    return impl;
  }

  /**
   * A helper function to respond to a request with an error.
   * @param response The description of the error to send to the user.
   *
   * @param httpExchange The exchange to send the error over.
   *
   * @throws IOException Thrown if the HttpExchange cannot communicate the error.
   */
  private void respondError(String response, HttpExchange httpExchange) throws IOException {
    httpExchange.getResponseHeaders().add("Content-Type", "text/plain");
    httpExchange.sendResponseHeaders(HTTP_ERR, response.length());
    httpExchange.getResponseBody().write(response.getBytes());
    httpExchange.close();
  }


  /**
   * A simple ping test. Responds with pong.
   */
  protected static class PingHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
      // Return a simple text message that says pong.
      httpExchange.getResponseHeaders().set("Content-Type", "text/plain");
      String response = "pong\n";
      httpExchange.sendResponseHeaders(HTTP_OK, response.getBytes().length);
      httpExchange.getResponseBody().write(response.getBytes());
      httpExchange.close();
    }
  }

  /**
   * Sending the appropriate shutdown key will gracefully shutdown the server.
   * This key is, by default, saved into the local file /tmp/corenlp.shutdown on the
   * machine the server was run from.
   */
  protected class ShutdownHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
      Map<String, String> urlParams = getURLParams(httpExchange.getRequestURI());
      httpExchange.getResponseHeaders().set("Content-Type", "text/plain");
      boolean doExit = false;
      String response = "Invalid shutdown key\n";
      if (urlParams.containsKey("key") && urlParams.get("key").equals(shutdownKey)) {
        response = "Shutdown successful!\n";
        doExit = true;
      }
      httpExchange.sendResponseHeaders(HTTP_OK, response.getBytes().length);
      httpExchange.getResponseBody().write(response.getBytes());
      httpExchange.close();
      if (doExit) {
        System.exit(0);
      }
    }
  }

  /**
   * Serve a file from the filesystem or classpath
   */
  protected static class FileHandler implements HttpHandler {
    private final String content;
    public FileHandler(String fileOrClasspath) throws IOException {
      this.content = IOUtils.slurpReader(IOUtils.readerFromString(fileOrClasspath));
    }
    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
      httpExchange.getResponseHeaders().set("Content-Type", "text/html");
      httpExchange.sendResponseHeaders(HTTP_OK, content.getBytes().length);
      httpExchange.getResponseBody().write(content.getBytes());
      httpExchange.close();
    }
  }

  /**
   * The main handler for taking an annotation request, and annotating it.
   */
  protected class CoreNLPHandler implements HttpHandler {
    /**
     * The default properties to use in the absence of anything sent by the client.
     */
    public final Properties defaultProps;

    /**
     * Create a handler for accepting annotation requests.
     * @param props The properties file to use as the default if none were sent by the client.
     */
    public CoreNLPHandler(Properties props) {
      defaultProps = props;
    }

    /**
     * Get the response data type to send to the client, based off of the output format requested from
     * CoreNLP.
     *
     * @param props The properties being used by CoreNLP.
     * @param of The output format being output by CoreNLP.
     *
     * @return An identifier for the type of the HTTP response (e.g., 'text/json').
     */
    public String getContentType(Properties props, StanfordCoreNLP.OutputFormat of) {
      switch(of) {
        case JSON:
          return "text/json";
        case TEXT:
        case CONLL:
          return "text/plain";
        case XML:
          return "text/xml";
        case SERIALIZED:
          String outputSerializerName = props.getProperty("outputSerializer");
          if (outputSerializerName != null &&
              outputSerializerName.equals(ProtobufAnnotationSerializer.class.getName())) {
            return "application/x-protobuf";
          }
        default:
          return "application/octet-stream";
      }
    }

    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
      // Set common response headers
      httpExchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");

      // Get sentence.
      Properties props;
      Annotation ann;
      StanfordCoreNLP.OutputFormat of;
      log("[" + httpExchange.getRemoteAddress() + "] Received message");
      try {
        props = getProperties(httpExchange);
        ann = getDocument(props, httpExchange);
        of = StanfordCoreNLP.OutputFormat.valueOf(props.getProperty("outputFormat", "json").toUpperCase());
        // Handle direct browser connections (i.e., not a POST request).
        if (ann.get(CoreAnnotations.TextAnnotation.class).length() == 0) {
          log("[" + httpExchange.getRemoteAddress() + "] Interactive connection");
          staticPageHandle.handle(httpExchange);
          return;
        }
        log("[" + httpExchange.getRemoteAddress() + "] API call");
      } catch (Exception e) {
        // Return error message.
        e.printStackTrace();
        String response = e.getMessage();
        httpExchange.getResponseHeaders().add("Content-Type", "text/plain");
        httpExchange.sendResponseHeaders(HTTP_BAD_INPUT, response.length());
        httpExchange.getResponseBody().write(response.getBytes());
        httpExchange.close();
        return;
      }

      try {
        // Annotate
        StanfordCoreNLP pipeline = mkStanfordCoreNLP(props);
        Future<Annotation> completedAnnotationFuture = corenlpExecutor.submit(() -> {
          pipeline.annotate(ann);
          return ann;
        });
        Annotation completedAnnotation = completedAnnotationFuture.get(5, TimeUnit.SECONDS);

        // Get output
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        StanfordCoreNLP.createOutputter(props, AnnotationOutputter.getOptions(pipeline)).accept(completedAnnotation, os);
        os.close();
        byte[] response = os.toByteArray();

        httpExchange.getResponseHeaders().add("Content-Type", getContentType(props, of));
        httpExchange.getResponseHeaders().add("Content-Length", Integer.toString(response.length));
        httpExchange.sendResponseHeaders(HTTP_OK, response.length);
        httpExchange.getResponseBody().write(response);
        httpExchange.close();
      } catch (TimeoutException e) {
        respondError("CoreNLP request timed out", httpExchange);
      } catch (Exception e) {
        // Return error message.
        respondError(e.getClass().getName() + ": " + e.getMessage(), httpExchange);
      }
    }

    /**
     * Parse the parameters of a connection into a CoreNLP properties file that can be passed into
     * {@link StanfordCoreNLP}, and used in the I/O stages.
     *
     * @param httpExchange The http exchange; effectively, the request information.
     *
     * @return A {@link Properties} object corresponding to a combination of default and passed properties.
     *
     * @throws UnsupportedEncodingException Thrown if we could not decode the key/value pairs with UTF-8.
     */
    private Properties getProperties(HttpExchange httpExchange) throws UnsupportedEncodingException {
      // Load the default properties
      Properties props = new Properties();
      defaultProps.entrySet().stream()
          .forEach(entry -> props.setProperty(entry.getKey().toString(), entry.getValue().toString()));

      // Try to get more properties from query string.
      Map<String, String> urlParams = getURLParams(httpExchange.getRequestURI());
      if (urlParams.containsKey("properties")) {
        StringUtils.decodeMap(URLDecoder.decode(urlParams.get("properties"), "UTF-8")).entrySet()
            .forEach(entry -> props.setProperty(entry.getKey(), entry.getValue()));
      } else if (urlParams.containsKey("props")) {
        StringUtils.decodeMap(URLDecoder.decode(urlParams.get("properties"), "UTF-8")).entrySet()
            .forEach(entry -> props.setProperty(entry.getKey(), entry.getValue()));
      }

      // Make sure the properties compile
      props.setProperty("annotators", StanfordCoreNLP.ensurePrerequisiteAnnotators(props.getProperty("annotators").split("[, \t]+")));

      return props;
    }
  }



  /**
   * A handler for matching TokensRegex patterns against text.
   */
  protected class TokensRegexHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
      // Set common response headers
      httpExchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");

      Future<String> json = corenlpExecutor.submit(() -> {
        try {
          // Get the document
          Properties props = new Properties() {{
            setProperty("annotators", "tokenize,ssplit,pos,lemma,ner");
          }};
          Annotation doc = getDocument(props, httpExchange);
          if (!doc.containsKey(CoreAnnotations.SentencesAnnotation.class)) {
            StanfordCoreNLP pipeline = mkStanfordCoreNLP(props);
            pipeline.annotate(doc);
          }

          // Construct the matcher
          Map<String, String> params = getURLParams(httpExchange.getRequestURI());
          // (get the pattern)
          if (!params.containsKey("pattern")) {
            respondError("Missing required parameter 'pattern'", httpExchange);
            return "";
          }
          String pattern = params.get("pattern");
          // (get whether to filter / find)
          String filterStr = params.getOrDefault("filter", "false");
          final boolean filter = filterStr.trim().isEmpty() || "true".equalsIgnoreCase(filterStr.toLowerCase());
          // (create the matcher)
          final TokenSequencePattern regex = TokenSequencePattern.compile(pattern);

          // Run TokensRegex
          return JSONOutputter.JSONWriter.objectToJSON((docWriter) -> {
            if (filter) {
              // Case: just filter sentences
              docWriter.set("sentences", doc.get(CoreAnnotations.SentencesAnnotation.class).stream().map(sentence ->
                      regex.matcher(sentence.get(CoreAnnotations.TokensAnnotation.class)).matches()
              ).collect(Collectors.toList()));
            } else {
              // Case: find matches
              docWriter.set("sentences", doc.get(CoreAnnotations.SentencesAnnotation.class).stream().map(sentence -> (Consumer<JSONOutputter.Writer>) (JSONOutputter.Writer sentWriter) -> {
                List<CoreLabel> tokens = sentence.get(CoreAnnotations.TokensAnnotation.class);
                TokenSequenceMatcher matcher = regex.matcher(tokens);
                int i = 0;
                while (matcher.find()) {
                  sentWriter.set(Integer.toString(i), (Consumer<JSONOutputter.Writer>) (JSONOutputter.Writer matchWriter) -> {
                    matchWriter.set("text", matcher.group());
                    matchWriter.set("begin", matcher.start());
                    matchWriter.set("end", matcher.end());
                    for (int groupI = 0; groupI < matcher.groupCount(); ++groupI) {
                      SequenceMatchResult.MatchedGroupInfo<CoreMap> info = matcher.groupInfo(groupI + 1);
                      matchWriter.set(info.varName == null ? Integer.toString(groupI + 1) : info.varName, (Consumer<JSONOutputter.Writer>) groupWriter -> {
                        groupWriter.set("text", info.text);
                        if (info.nodes.size() > 0) {
                          groupWriter.set("begin", info.nodes.get(0).get(CoreAnnotations.IndexAnnotation.class) - 1);
                          groupWriter.set("end", info.nodes.get(info.nodes.size() - 1).get(CoreAnnotations.IndexAnnotation.class));
                        }
                      });
                    }
                  });
                  i += 1;
                }
                sentWriter.set("length", i);
              }));
            }
          });
        } catch (Exception e) {
          e.printStackTrace();
          try {
            respondError(e.getClass().getName() + ": " + e.getMessage(), httpExchange);
          } catch (IOException ignored) {
          }
        }
        return "";
      });

      // Send response
      byte[] response = new byte[0];
      try {
        response = json.get(5, TimeUnit.SECONDS).getBytes();
      } catch (InterruptedException | ExecutionException | TimeoutException e) {
        respondError("Timeout when executing TokensRegex query", httpExchange);
      }
      if (response.length > 0) {
        httpExchange.getResponseHeaders().add("Content-Type", "text/json");
        httpExchange.getResponseHeaders().add("Content-Length", Integer.toString(response.length));
        httpExchange.sendResponseHeaders(HTTP_OK, response.length);
        httpExchange.getResponseBody().write(response);
        httpExchange.close();
      }
    }
  }



  /**
   * A handler for matching semgrex patterns against dependency trees.
   */
  protected class SemgrexHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
      // Set common response headers
      httpExchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");

      Future<String> json = corenlpExecutor.submit(() -> {
        try {
          // Get the document
          Properties props = new Properties() {{
            setProperty("annotators", "tokenize,ssplit,pos,lemma,ner,depparse");
          }};
          Annotation doc = getDocument(props, httpExchange);
          if (!doc.containsKey(CoreAnnotations.SentencesAnnotation.class)) {
            StanfordCoreNLP pipeline = mkStanfordCoreNLP(props);
            pipeline.annotate(doc);
          }

          // Construct the matcher
          Map<String, String> params = getURLParams(httpExchange.getRequestURI());
          // (get the pattern)
          if (!params.containsKey("pattern")) {
            respondError("Missing required parameter 'pattern'", httpExchange);
            return "";
          }
          String pattern = params.get("pattern");
          // (get whether to filter / find)
          String filterStr = params.getOrDefault("filter", "false");
          final boolean filter = filterStr.trim().isEmpty() || "true".equalsIgnoreCase(filterStr.toLowerCase());
          // (create the matcher)
          final SemgrexPattern regex = SemgrexPattern.compile(pattern);

          // Run TokensRegex
          return JSONOutputter.JSONWriter.objectToJSON((docWriter) -> {
            if (filter) {
              // Case: just filter sentences
              docWriter.set("sentences", doc.get(CoreAnnotations.SentencesAnnotation.class).stream().map(sentence ->
                      regex.matcher(sentence.get(SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation.class)).matches()
              ).collect(Collectors.toList()));
            } else {
              // Case: find matches
              docWriter.set("sentences", doc.get(CoreAnnotations.SentencesAnnotation.class).stream().map(sentence -> (Consumer<JSONOutputter.Writer>) (JSONOutputter.Writer sentWriter) -> {
                SemgrexMatcher matcher = regex.matcher(sentence.get(SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation.class));
                int i = 0;
                while (matcher.find()) {
                  sentWriter.set(Integer.toString(i), (Consumer<JSONOutputter.Writer>) (JSONOutputter.Writer matchWriter) -> {
                    IndexedWord match = matcher.getMatch();
                    matchWriter.set("text", match.word());
                    matchWriter.set("begin", match.index() - 1);
                    matchWriter.set("end", match.index());
                    for (String capture : matcher.getNodeNames()) {
                      matchWriter.set("$" + capture, (Consumer<JSONOutputter.Writer>) groupWriter -> {
                        IndexedWord node = matcher.getNode(capture);
                        groupWriter.set("text", node.word());
                        groupWriter.set("begin", node.index() - 1);
                        groupWriter.set("end", node.index());
                      });
                    }
                  });
                  i += 1;
                }
                sentWriter.set("length", i);
              }));
            }
          });
        } catch (Exception e) {
          e.printStackTrace();
          try {
            respondError(e.getClass().getName() + ": " + e.getMessage(), httpExchange);
          } catch (IOException ignored) {
          }
        }
        return "";
      });

      // Send response
      byte[] response = new byte[0];
      try {
        response = json.get(5, TimeUnit.SECONDS).getBytes();
      } catch (InterruptedException | ExecutionException | TimeoutException e) {
        respondError("Timeout when executing Semgrex query", httpExchange);
      }
      if (response.length > 0) {
        httpExchange.getResponseHeaders().add("Content-Type", "text/json");
        httpExchange.getResponseHeaders().add("Content-Length", Integer.toString(response.length));
        httpExchange.sendResponseHeaders(HTTP_OK, response.length);
        httpExchange.getResponseBody().write(response);
        httpExchange.close();
      }
    }
  }





  /**
   * Run the server.
   * This method registers the handlers, and initializes the HTTP server.
   */
  @Override
  public void run() {
    try {
      server = HttpServer.create(new InetSocketAddress(serverPort), 0); // 0 is the default 'backlog'
      server.createContext("/", new CoreNLPHandler(defaultProps));
      server.createContext("/tokensregex", new TokensRegexHandler());
      server.createContext("/semgrex", new SemgrexHandler());
      server.createContext("/corenlp-brat.js", new FileHandler("edu/stanford/nlp/pipeline/demo/corenlp-brat.js"));
      server.createContext("/corenlp-brat.cs", new FileHandler("edu/stanford/nlp/pipeline/demo/corenlp-brat.css"));
      server.createContext("/ping", new PingHandler());
      server.createContext("/shutdown", new ShutdownHandler());
      server.setExecutor(serverExecutor);
      server.start();
      log("StanfordCoreNLPServer listening at " + server.getAddress());
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  /**
   * The main method.
   * Read the command line arguments and run the server.
   *
   * @param args The command line arguments
   *
   * @throws IOException Thrown if we could not start / run the server.
   */
  public static void main(String[] args) throws IOException {
    int port = DEFAULT_PORT;
    if(args.length > 0) {
      port = Integer.parseInt(args[0]);
    }
    StanfordCoreNLPServer server = new StanfordCoreNLPServer(port);
    server.run();
  }
}

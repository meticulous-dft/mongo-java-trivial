package generator;

import static com.mongodb.client.model.Filters.eq;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Updates;
import com.mongodb.connection.TransportSettings;
import com.mongodb.event.ServerHeartbeatFailedEvent;
import com.mongodb.event.ServerHeartbeatStartedEvent;
import com.mongodb.event.ServerHeartbeatSucceededEvent;
import com.mongodb.event.ServerMonitorListener;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.ServerApi;
import com.mongodb.ServerApiVersion;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import java.util.stream.Collectors;
import net.datafaker.Address;
import net.datafaker.Animal;
import net.datafaker.Coffee;
import net.datafaker.Faker;
import net.datafaker.Name;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;


public class Main {
  //private static final int BATCH_SIZE = 1000;
  private static final int BATCH_SIZE = 10; //intentionally low for volume
  private static int TARGET_OPEN = 300;
  static int READ_SPIN = 60;

  private static <R extends Runnable> List<R> startThreads(final Supplier<R> runnableSupplier, int n) {
    final List<R> runnableList = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      R runnable = runnableSupplier.get();
      runnableList.add(runnable);
      Thread t = new Thread(runnable);
      t.setDaemon(true);
      t.start();
    }
    return runnableList;
  }

  public static void main(String[] args) throws InterruptedException, ParseException {
    final Options options = new Options();
    options.addOption("h", "help", false, "print this message");
    options.addOption("a", "uri", true, "mongodb uri");
    options.addOption("u", "updaterThreads", true, "number of treads to update documents");
    options.addOption("l", "minId", true, "min document id for updater threads");
    options.addOption("h", "maxId", true, "max document id for updater threads");
    options.addOption("i", "inserterThreads", true, "number of treads to insert new documents");
    options.addOption("s", "startId", true, "starting document id for inserter threads");
    options.addOption("n", "networkType", true, "network type to use");
    options.addOption("f", "frontendCNAME", true, "CNAME for LB frontend");
    options.addOption("p", "frontendPorts", true, "Comma delimited list of ports on frontend to check every .5 seconds");
    options.addOption("r", "read", true, "num of read only threads");
    options.addOption("t", "targetOpen", true, "target # of dangling reads per thread");
    options.addOption("w", "cpuWasters", true, "Threads to spin and do nothing");
    options.addOption("rw", "cpuWastePerReadOp", true, "Time to waste in read, in s");
    options.addOption("ee", "errorEndpoint", true, "Endpoint to POST errors to");
    DefaultParser parser = new DefaultParser();
    CommandLine cmd = parser.parse(options, args);

    if (cmd.hasOption("help")) {
      HelpFormatter helpFormatter = new HelpFormatter();
      helpFormatter.printHelp("java -jar mt.jar", options);
      return;
    }

    if (cmd.hasOption("t")){
      TARGET_OPEN = Integer.parseInt(cmd.getOptionValue("t"));
    }

    if(cmd.hasOption("rw")){
      READ_SPIN = Integer.parseInt(cmd.getOptionValue("rw"));
    }

    String uri;
    if (!cmd.hasOption("uri")) {
      System.out.println("must specify server URI, e.g. mongodb+srv://dmitry:<password>@cluster0.z1nie.mongodb-dev.net/?retryWrites=true&w=majority");
      return;
    } else {
      uri = cmd.getOptionValue("uri");
    }

    int numUpdaters = 0;
    if (cmd.hasOption("u")) {
      numUpdaters = Integer.parseInt(cmd.getOptionValue("u"));
      if ((!cmd.hasOption("l") || !cmd.hasOption("h"))) {
        System.out.println("must specify minId and maxId for updater threads");
        return;
      }
    }
    final long minId = cmd.hasOption("l")? Long.parseLong(cmd.getOptionValue("l")): 0;
    final long maxId = cmd.hasOption("h")? Long.parseLong(cmd.getOptionValue("h")): 0;

    int numProducers = 0;
    int numInserters = 0;
    if (cmd.hasOption("i")) {
      numInserters = Integer.parseInt(cmd.getOptionValue("i"));
      numProducers = numInserters / 3 + 1;
    }

    long startId = 1;
    if (cmd.hasOption("s")) {
      startId = Long.parseLong(cmd.getOptionValue("s"));
    }

    ConnectionString connectionString = new ConnectionString(uri);

    var settingsBuilder =MongoClientSettings.builder()
        .applyConnectionString(connectionString)
        .serverApi(ServerApi.builder()
            .version(ServerApiVersion.V1)
            .build());
    if (cmd.hasOption("n")){
      System.out.println("Using netty stack");
      settingsBuilder.transportSettings(TransportSettings.nettyBuilder()
          .build());
    } else{
      System.out.println("Using default transport");
    }

    final String frontendId;
    ArrayList<Integer> ports = new ArrayList<>();
    if(cmd.hasOption("f")) {
      if (!cmd.hasOption("p")) {
        System.out.println("Frontend needs ports specified");
        return;
      }
      frontendId = cmd.getOptionValue("f");
      Arrays.stream(cmd.getOptionValue("p").split(",")).map(Integer::parseInt)
              .forEach(ports::add);
    } else{
      frontendId = null;
    }

    final int numWasters;
    if(cmd.hasOption("w")) {
      numWasters = Integer.parseInt(cmd.getOptionValue("w"));
    } else{
      numWasters = 0;
    }

    settingsBuilder.applyToConnectionPoolSettings(builder -> builder.maxSize(1_500));
    final Logger heartbeatLogger = LoggerFactory.getLogger("monitor");
    settingsBuilder.applyToServerSettings(builder ->
    builder.addServerMonitorListener(
        new ServerMonitorListener() {
          @Override
          public void serverHearbeatStarted(final ServerHeartbeatStartedEvent event) {
            heartbeatLogger.info("Heartbeat started {}", event);
          }

          @Override
          public void serverHeartbeatSucceeded(final ServerHeartbeatSucceededEvent event) {
            heartbeatLogger.info("Heartbeat succeeded {}", event);
          }

          @Override
          public void serverHeartbeatFailed(final ServerHeartbeatFailedEvent event) {
            heartbeatLogger.error("Heartbeat failed {}", event);
          }
        }
    ));

    MongoClientSettings settings = settingsBuilder.build();
    AtomicLong docId = new AtomicLong(startId);
    DocumentGenerator documentGenerator = new DocumentGenerator(docId);
    final Logger throughputLogger = LoggerFactory.getLogger("throughput");

    try (MongoClient mongoClient = MongoClients.create(settings)) {
      MongoDatabase database = mongoClient.getDatabase("test_smaller");
      MongoCollection<Document> collection = database.getCollection("test");
      var thing = collection.createIndex(new Document("whatWasId", 1));
      var indexName = Mono.from(thing).block();
      throughputLogger.error("got index name " + indexName);

      //ArrayBlockingQueue<Document> queue = new ArrayBlockingQueue<>(100000);
      //AtomicLong counter = new AtomicLong(0);
      AtomicLong updateCounter = new AtomicLong(0);

      //List<Producer> producers = startThreads(() -> new Producer(documentGenerator, queue), numProducers);
      List<Inserter> inserters = startThreads(() -> new Inserter(collection), numInserters);

      List<Updater> updaters = startThreads(() -> new Updater(updateCounter,
          documentGenerator, collection, minId, maxId), numUpdaters);

      final int numReaders;
      if (cmd.hasOption("r")) {
        numReaders = Integer.parseInt(cmd.getOptionValue("r"));
      } else{
        numReaders = 0;
      }
      List<Reader> readers = startThreads(() -> new Reader(collection), numReaders);
      //List<Reader> readers = startThreads(() -> new Reader(settings), numReaders);
      List<CpuWaster> wasters = startThreads(CpuWaster::new, numWasters);

      final List<SocketChecker> socketCheckers = ports.stream().map( port ->
      startThreads(() -> new SocketChecker(frontendId, port), 1)
          .get(0))
          .toList();


      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        //producers.forEach(Producer::stop);
        inserters.forEach(Inserter::stop);
        updaters.forEach(Updater::stop);
        socketCheckers.forEach(SocketChecker::stop);
        readers.forEach(Reader::stop);
        wasters.forEach(CpuWaster::stop);
      }));

      long before = System.nanoTime();
      while (true) {
        Thread.sleep(3000);
        long after = System.nanoTime();
        long timeDelta = after - before;

        //long insertCount = counter.get();
        long insertCount = inserters.stream().map(x -> x.myCount).reduce(0L, Long::sum);
        long updateCount = updaters.stream().map( x -> x.myCount).reduce(0L, Long::sum);

        if (numInserters > 0) {
          throughputLogger.trace("InsertCount = " + insertCount);
          //System.out.println("queue size: " + queue.size());
          throughputLogger.trace("avg writes/s " +  insertCount / (timeDelta / 1e9));
        }
//        if (numUpdaters > 0) {
//          throughputLogger.trace("UpdateCount = " + updateCount);
//          throughputLogger.trace("avg updates/s " + updateCount / (timeDelta / 1e9));
//        }
      }

    }
  }

  public static class CpuWaster implements Runnable{
    private volatile boolean flag = true;

    @Override
    public void run(){
      while (flag){
        // Perform some calculations to waste CPU
        double x = Math.random();
        double y = Math.random();
        double z = Math.pow(x, y);
      }
    }
    public void stop(){
      flag = false;
    }
  }

  public static class Reader implements Runnable{
    private final Logger updaterLogger=LoggerFactory.getLogger("timings");
    private final MongoCollection<Document> collection;
    private volatile boolean flag = true;
    private int numOpen = 0;

    public Reader(final MongoCollection<Document> collection){
      this.collection = collection;
    }

    public Reader(final MongoClientSettings settings){
      var connection = MongoClients.create(settings);
      collection = connection.getDatabase("bad_idea").getCollection("against_docs_and_common_sense");
    }

    @Override
    public void run() {
      while (flag) {
        if (numOpen < TARGET_OPEN) {
          final var startTime = System.nanoTime();
          var findResult = collection.find(new Document()).limit(1);
          findResult.subscribe(new Subscriber<Document>() {
            @Override
            public void onSubscribe(final Subscription s) {
              numOpen += 1;
              s.request(Long.MAX_VALUE);
//              try {
//                //Thread.sleep(30_000);
//              } catch (InterruptedException pE) {
//                updaterLogger.error("Got exception on crazy wait", pE);
//              }
            }

            @Override
            public void onNext(final Document pDocument) {
              updaterLogger.info("got next");

            }

            @Override
            public void onError(final Throwable t) {
              updaterLogger.error("Exception on read ", t);
              numOpen -= 1;
            }

            @Override
            public void onComplete() {
              final var endTime = System.nanoTime();
              final var timeMs = (endTime - startTime) / 100_000;
              if (timeMs > 200) {
                updaterLogger.info("Read took a long time " + timeMs + " ms");
              } else {
                updaterLogger.info("Read took " + timeMs + " ms");
              }
              numOpen -= 1;
              long spinEnd = System.currentTimeMillis() + (READ_SPIN * 1000L); // 1 minute from now
              while (System.currentTimeMillis() < spinEnd) {
                // Perform some calculations to waste CPU
                double x = Math.random();
                double y = Math.random();
                double z = Math.pow(x, y);
              }
              updaterLogger.info("Done wasting CPU");
            }
          });

        }
      }
    }

    void stop() {
      flag = false;
    }
  }

  public static class Updater implements Runnable {
    private final Random random = new Random();
    private final MongoCollection<Document> collection;
    private final DocumentGenerator generator;
    private volatile boolean flag = true;
    private final AtomicLong counter;
    private final long minId;
    private final long maxId;
    private final Logger updaterLogger = LoggerFactory.getLogger("timings");
    public long myCount = 0;

    public Updater(AtomicLong counter, DocumentGenerator generator, MongoCollection<Document> collection, long minId, long maxId) {
      this.generator = generator;
      this.collection = collection;
      this.counter = counter;
      this.minId = minId;
      this.maxId = maxId;
    }

    @Override
    public void run() {
      while (flag) {
        long id = random.nextLong(minId, maxId + 1);
        Document query = new Document().append("whatWasId", id);
        try {
          final var startTime = System.nanoTime();
          var replacePublisher = collection.findOneAndUpdate(query, Updates.set("whatWasId", id+1), new FindOneAndUpdateOptions().projection(new Document().append("_id", 1)));
          Mono.from(replacePublisher).block();
          final var endTime = System.nanoTime();
          long duration = (endTime - startTime) / 1_000_000;
          if(duration >= 200){
            updaterLogger.warn("update took a long time " + duration);
          } else {
            updaterLogger.trace("update took " + duration);
          }

          counter.incrementAndGet();
        } catch (Exception e) {
          updaterLogger.error("update failed: " + e);
        }
      }
    }

    public void stop() {
      flag = false;
    }
  }


  public static class Inserter implements Runnable {
    private final MongoCollection<Document> collection;
    private ArrayBlockingQueue<Document> queue;
    private volatile boolean flag = true;
    private AtomicLong counter;
    private final Logger inserterLogger = LoggerFactory.getLogger("timings");
    private final DocumentGenerator _generator = new DocumentGenerator(null);
    public long myCount = 0;

    public Inserter(MongoCollection<Document> collection,
        ArrayBlockingQueue<Document> queue,
        AtomicLong counter) {
      this.collection = collection;
      this.queue = queue;
      this.counter = counter;
    }
    public Inserter(MongoCollection<Document> collection) {
      this.collection = collection;
    }

    @Override
    public void run() {
      final int batchSize = BATCH_SIZE;
      while (flag) {
        //queue.drainTo(buffer, batchSize);

        final List<Document> buffer = new ArrayList<>(batchSize);
        for(var i = 0; i < batchSize; i++) {
          buffer.add(_generator.generateOne());
        }


        try {
          final var startTime = System.nanoTime();
          var result = collection.insertMany(buffer);
          Mono.from(result).block();
          final var endTime = System.nanoTime();
          final var duration_millis = (endTime - startTime) / 1_000_000;
          if(duration_millis >= 200){
            inserterLogger.warn("insert took a long time " + duration_millis);
          } else {
            inserterLogger.trace("insert took " + (endTime - startTime));
          }
          myCount += BATCH_SIZE;
          //buffer.clear();
        } catch (Exception e) {
          inserterLogger.error("Got exception: " + e);
        }
        try {
          Thread.sleep(200);
        } catch (InterruptedException pE) {
          inserterLogger.error("Got exception on sleep: " + pE);
        }
      }
    }

    public void stop() {
      flag = false;
    }
  }

  public static class Producer implements Runnable {
    private final ArrayBlockingQueue<Document> queue;
    private final DocumentGenerator generator;
    private volatile boolean flag = true;

    public Producer(DocumentGenerator generator, ArrayBlockingQueue<Document> queue) {
      this.generator = generator;
      this.queue = queue;
    }

    @Override
    public void run() {
      while (flag) {
        try {
          queue.put(generator.generateOne());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }

    public void stop() {
      flag = false;
    }
  }

  public static class DocumentGenerator {
    private final AtomicLong uniqDocId;
    private final Faker faker = Faker.instance();

    public DocumentGenerator(AtomicLong uniqDocId) {
      this.uniqDocId = uniqDocId;
    }

    public Document generateOne() {
      return generateOne((long) (Math.random()*Long.MAX_VALUE));
    }

    public Document generateOne(long docId) {
      final Name name = faker.name();
//      final Address address = faker.address();
//      final Animal pet = faker.animal();
//      final Coffee coffee = faker.coffee();
      return new Document()
          //.append("_id", new ObjectId())
          .append("whatWasId", docId)
          .append("firstName", name.firstName());
      //)
//          .append("lastName", name.lastName())
//          .append("address", address.fullAddress())
//          .append("address_normalized", new Document()
//              .append("streetAddress", address.streetAddress())
//              .append("city", address.cityName())
//              .append("state", address.state())
//              .append("zipCode", address.zipCode())
//              .append("country", address.country())
//          )
//          .append("pet", new Document()
//              .append("name", pet.name())
//              .append("scientificName", pet.scientificName())
//          )
//          .append("lastWords", faker.famousLastWords().lastWords())
//          .append("quote", faker.bigBangTheory().quote())
//          .append("favoriteCoffee", new Document()
//              .append("blend", coffee.blendName())
//              .append("descriptor", coffee.descriptor())
//              .append("notes", coffee.notes())
//          )
//          .append("note", faker.elderScrolls().quote())
//          .append("birthday", faker.date().birthday());
    }
  }

  public static class SocketChecker implements Runnable{
    final String targetURI;
    final int targetPort;
    boolean flag = true;
    final Logger socketLogger = LoggerFactory.getLogger("socket");

    public SocketChecker(final String targetURI, final int targetPort) {
      this.targetURI = targetURI;
      this.targetPort = targetPort;
    }

    @Override
    public void run() {
      while (flag) {
        final var startTime = System.nanoTime();
        try(Socket socket = new Socket()) {
          final var endpoint = new InetSocketAddress(targetURI, targetPort);
          socket.connect(endpoint, 1000); // timeout set to 1s
          final var stopTime = System.nanoTime();
          final var duration_millis = (stopTime - startTime) / 1_000_000;
          socketLogger.trace("Socket connection for {} took {}", targetPort, duration_millis);
        } catch (UnknownHostException pE) {
          socketLogger.debug("DNS socket error", pE);
        } catch (IOException pE) {
          socketLogger.debug("socket IO exception for port {}", targetPort, pE);
        }
        try {
          Thread.sleep(5000);
        } catch (InterruptedException pE) {
          socketLogger.warn("socket interrupted for port {}", targetPort);
        }
      }
    }

    // invoke to stop
    public void stop() {
      flag = false;
    }

  }
}
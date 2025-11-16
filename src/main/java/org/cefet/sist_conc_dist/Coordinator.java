package org.cefet.sist_conc_dist;

import org.cefet.sist_conc_dist.enums.Message;
import org.cefet.sist_conc_dist.enums.Operation;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.*;

public class Coordinator extends BaseProcess{

    // sockets per pid
    private final ConcurrentHashMap<Integer, Socket> clientSockets = new ConcurrentHashMap<>();

    // queue of process ids requesting CS
    private final LinkedBlockingQueue<Integer> requestQueue = new LinkedBlockingQueue<>();

    // latches per pid for waiting for RELEASE
    private final ConcurrentHashMap<Integer, CountDownLatch> releaseLatches = new ConcurrentHashMap<>();

    private final File logFile = new File("coordinator_log.txt");
    private final Object logLock = new Object();

    public Coordinator(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        ServerSocket serverSocket = new ServerSocket(port);
        System.out.println("Coordinator iniciando na porta " + port);

        // thread: acceptor
        Thread acceptor = getAcceptorThread(serverSocket);
        acceptor.start();

        // dispatcher thread: take from requestQueue, send GRANT, wait for RELEASE
        Thread dispatcher = getDispatcherThread();
        dispatcher.start();

        // interface thread: reads console commands
        Thread uiThread = getUIThread();
        uiThread.setDaemon(true);
        uiThread.start();
    }

    private void printUiHelp() {
        System.out.println("Comandos: ");
        System.out.println("1 -> imprimir a fila de pedidos atual");
        System.out.println("2 -> imprimir quantas vezes cada processo foi atendido (do log)");
        System.out.println("3 -> encerrar a execução");
        System.out.println("h -> ajuda");
    }

    private void printServiceCounts() {
        ConcurrentHashMap<Integer, Integer> counts = new ConcurrentHashMap<>();
        if (!logFile.exists()) {
            System.out.println("Log não existe ainda.");
            return;
        }
        try (BufferedReader br = new BufferedReader(new FileReader(logFile))) {
            String l;
            while ((l = br.readLine()) != null) {
                if (l.contains("SENT_GRANT")) {
                    String[] parts = l.split("\\s+");
                    // assume last token is pid:x
                    for (String p : parts) {
                        if (p.startsWith("pid:")) {
                            int pid = Integer.parseInt(p.substring(4));
                            counts.merge(pid, 1, Integer::sum);
                        }
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        System.out.println("Atendimentos por processo:");
        counts.forEach((k, v) -> System.out.println("pid " + k + ": " + v));
    }

    private Thread getAcceptorThread(ServerSocket serverSocket){
        return new Thread(() -> {
            while (true) {
                try {
                    Socket s = serverSocket.accept();

                    Thread readerThread = getReaderThread(s);
                    readerThread.start();

                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }, "acceptor-thread");
    }

    private Thread getReaderThread(Socket s) {
        return new Thread(() -> {
            try (InputStream in = s.getInputStream()) {
                byte[] buf = new byte[F];
                while (true) {
                    //inicio leitura
                    int read = 0;

                    while (read < F) {
                        int r = in.read(buf, read, F - read); // espera mensagem do cliente no cano de input stream
                        if (r == -1) throw new EOFException("client disconnected");
                        read += r;
                    }
                    String msg = new String(buf, StandardCharsets.UTF_8);
                    // fim leitura
                    Message parsed = parseMessage(msg);
                    if (parsed == null) { // mensagem mal parseada
                        log("INVALID_MSG", -1);
                        continue;
                    }
                    int pid = parsed.getPid();

                    // registra o socket na lista de sockets se for a primeira vez.
                    clientSockets.putIfAbsent(pid, s);

                    Operation type = Operation.fromNumber(parsed.getType());

                    // inicio tratamento tipo
                    if (type == Operation.REQ) {
                        log("RECEIVED_REQUEST", pid);
                        requestQueue.put(pid);
                    } else if (type == Operation.REL) {
                        log("RECEIVED_RELEASE", pid);
                        // signal latch if present
                        CountDownLatch latch = releaseLatches.get(pid);
                        if (latch != null) {
                            latch.countDown();
                        } else {
                            log("RELEASE_WITHOUT_LATCH", pid);
                        }
                    } else {
                        log("UNKNOWN_TYPE", pid);
                    }
                    //fim tratamento tipo
                }
            } catch (EOFException eof) {
                System.out.println("Client disconnected: " + s);
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        }, "reader-" + s.getRemoteSocketAddress());
    }

    private Thread getDispatcherThread(){
        return new Thread(() -> {
            while (true) {
                try {
                    int pid = requestQueue.take(); // bloqueia se nao tiver requisicao e espera ate ter.
                    log("DEQUEUED_REQUEST", pid);
                    Socket s = clientSockets.get(pid);
                    if (s == null || s.isClosed()) {
                        log("SKIP_NO_SOCKET", pid);
                        continue; // pula se socket desconectado
                    }
                    // prepara o latch
                    CountDownLatch latch = new CountDownLatch(1);
                    releaseLatches.put(pid, latch); // envia na lista de releases.

                    // envia GRANT
                    sendMessage(s, buildMessage(Operation.GNT.getEnumerated(), pid));
                    log("SENT_GRANT", pid);

                    try {
                        latch.await(); // Bloqueia ate o countDown ( quando release do cliente chegar no reader )
                        log("RECEIVED_RELEASE_FOR", pid);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        releaseLatches.remove(pid);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }, "dispatcher-thread");
    }

    private Thread getUIThread(){
        return new Thread(() -> {
            try (BufferedReader console = new BufferedReader(new InputStreamReader(System.in))) {
                String line;
                printUiHelp();
                while ((line = console.readLine()) != null) {
                    line = line.trim();
                    if (line.equalsIgnoreCase("1")) {
                        System.out.println("Current queue: " + this.requestQueue);
                    } else if (line.equalsIgnoreCase("2")) {
                        printServiceCounts();
                    } else if (line.equalsIgnoreCase("3")) {
                        System.out.println("Shutting down coordinator...");
                        System.exit(0);
                    } else if (line.equalsIgnoreCase("h") || line.equalsIgnoreCase("help")) {
                        printUiHelp();
                    } else {
                        System.out.println("Comando desconhecido. 'h' para ajuda.");
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }, "ui-thread");
    }


    private void sendMessage(Socket s, String msg) throws IOException {
        OutputStream out = s.getOutputStream();
        out.write(msg.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private String buildMessage(int type, int pid) {
        String base = type + "|" + pid + "|";
        // pad with zeros/spaces to length F
        StringBuilder sb = new StringBuilder(base);
        while (sb.toString().getBytes(StandardCharsets.UTF_8).length < F) {
            sb.append('0');
        }
        String res = sb.toString().substring(0, F);
        return res;
    }

    private Message parseMessage(String padded) {
        String trimmed = padded.replace('\0', ' ').trim();
        String[] parts = trimmed.split("\\|");
        if (parts.length < 2) return null;
        try {
            int type = Integer.parseInt(parts[0]);
            int pid = Integer.parseInt(parts[1]);
            return new Message(type, pid);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void log(String event, int pid) {
        String time = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        String line = String.format("%s %s pid:%d", time, event, pid);
        synchronized (logLock) {
            try (FileWriter fw = new FileWriter(logFile, true)) {
                fw.write(line + System.lineSeparator());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }



    public static void main(String[] args) throws Exception {
        int port = 5000;
        if (args.length >= 1) port = Integer.parseInt(args[0]);
        Coordinator c = new Coordinator(port);
        c.start();
    }
}

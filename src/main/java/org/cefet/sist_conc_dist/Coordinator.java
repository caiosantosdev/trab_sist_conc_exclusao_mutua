package org.cefet.sist_conc_dist;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.*;

public class Coordinator {
    // Message types
    private static final int REQ = 1;
    private static final int GNT = 2;
    private static final int REL = 3;

    private static final int F = 64; // fixed message length in bytes
    private final int port;

    // sockets per pid
    // explicar
    private final ConcurrentHashMap<Integer, Socket> clientSockets = new ConcurrentHashMap<>();

    // queue of process ids requesting CS
    // explicar
    private final LinkedBlockingQueue<Integer> requestQueue = new LinkedBlockingQueue<>();

    // latches per pid for waiting for RELEASE
    // explicar
    private final ConcurrentHashMap<Integer, CountDownLatch> releaseLatches = new ConcurrentHashMap<>();

    private final File logFile = new File("coordinator_log.txt");
    private final Object logLock = new Object();

    public Coordinator(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        ServerSocket serverSocket = new ServerSocket(port);
        System.out.println("Coordinator started on port " + port);

        // thread: acceptor
        Thread acceptor = new Thread(() -> {
            while (true) {
                try {
                    Socket s = serverSocket.accept();
                    // read pid once from socket (first message must be "HELLO|pid|...") or the client will send messages normally.
                    // We'll spawn a reader thread and the reader will register the socket when it receives first message with pid.
                    startReaderThread(s);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }, "acceptor-thread");
        acceptor.start();

        // dispatcher thread: take from requestQueue, send GRANT, wait for RELEASE
        Thread dispatcher = new Thread(() -> {
            while (true) {
                try {
                    int pid = requestQueue.take(); // blocks until a request present
                    log("DEQUEUED_REQUEST", pid);
                    Socket s = clientSockets.get(pid);
                    if (s == null || s.isClosed()) {
                        log("SKIP_NO_SOCKET", pid);
                        continue; // skip if disconnected
                    }
                    // prepare latch
                    CountDownLatch latch = new CountDownLatch(1); //explicar
                    releaseLatches.put(pid, latch);

                    // send GRANT
                    sendMessage(s, buildMessage(GNT, pid));
                    log("SENT_GRANT", pid);

                    // wait for RELEASE from that pid
                    try {
                        // perguntar
                        latch.await(); // blocks until RELEASE comes and counts down
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
        dispatcher.start();

        // interface thread: reads console commands
        Thread uiThread = new Thread(() -> {
            try (BufferedReader console = new BufferedReader(new InputStreamReader(System.in))) {
                String line;
                printUiHelp();
                while ((line = console.readLine()) != null) {
                    line = line.trim();
                    if (line.equalsIgnoreCase("1")) {
                        // print queue
                        System.out.println("Current queue: " + requestQueue);
                    } else if (line.equalsIgnoreCase("2")) {
                        // print how many times each process was served (we can derive from log)
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
        // simples: parse log and count GNT per pid
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

    private void startReaderThread(Socket s) {
        Thread t = new Thread(() -> {
            try (InputStream in = s.getInputStream()) {
                byte[] buf = new byte[F];
                while (true) {
                    int read = 0;
                    // explicar
                    while (read < F) {
                        int r = in.read(buf, read, F - read);
                        if (r == -1) throw new EOFException("client disconnected");
                        read += r;
                    }
                    String msg = new String(buf, StandardCharsets.UTF_8);
                    Message parsed = parseMessage(msg);
                    if (parsed == null) {
                        log("INVALID_MSG", -1);
                        continue;
                    }
                    int pid = parsed.pid;
                    // register socket if first time
                    clientSockets.putIfAbsent(pid, s);
                    if (parsed.type == REQ) {
                        log("RECEIVED_REQUEST", pid);
                        requestQueue.put(pid);
                    } else if (parsed.type == REL) {
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
                }
            } catch (EOFException eof) {
                System.out.println("Client disconnected: " + s);
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        }, "reader-" + s.getRemoteSocketAddress());
        t.start();
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

    private static class Message {
        int type, pid;
        Message(int t, int p) { type = t; pid = p; }
    }

    public static void main(String[] args) throws Exception {
        int port = 5000;
        if (args.length >= 1) port = Integer.parseInt(args[0]);
        Coordinator c = new Coordinator(port);
        c.start();
    }
}

package org.cefet.sist_conc_dist;

import org.cefet.sist_conc_dist.enums.Message;
import org.cefet.sist_conc_dist.enums.Operation;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;

public class Coordinator extends BaseProcess{

    // sockets per pid
    private final ConcurrentHashMap<Integer, Socket> clientSockets = new ConcurrentHashMap<>();

    // queue of process ids requesting CS
    private final LinkedBlockingQueue<Integer> requestQueue = new LinkedBlockingQueue<>();

    private Integer currentProcess = null;

    private final File logFile = new File("coordinator_log.txt");
    private final Object logLock = new Object();

    private final Object algorithmLock = new Object();

    public Coordinator(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        ServerSocket serverSocket = new ServerSocket(port);
        System.out.println("Coordinator iniciando na porta " + port);

        // thread: acceptor
        Thread acceptor = getAcceptorThread(serverSocket);
        acceptor.start();

        // interface thread: reads console commands
        Thread uiThread = getUIThread();
        uiThread.setDaemon(true);
        uiThread.start();
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
                    int read = 0;
                    while (read < F) {
                        int r = in.read(buf, read, F - read);
                        if (r == -1) throw new EOFException("client disconnected");
                        read += r;
                    }
                    String msgStr = new String(buf, StandardCharsets.UTF_8);
                    Message parsed = parseMessage(msgStr);

                    if (parsed != null) {
                        clientSockets.putIfAbsent(parsed.getPid(), s);

                        // Dispatcher aqui
                        processMessage(parsed);
                    }
                }
            } catch (EOFException eof) {
                System.out.println("Client disconnected: " + s);
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        }, "reader-" + s.getRemoteSocketAddress());
    }

    private synchronized void processMessage(Message m) throws IOException, InterruptedException {
        Operation type = Operation.fromNumber(m.getType());
        int pid = m.getPid();
        System.out.println("Chegou process message de pid: " + pid);
        System.out.println(type);


        if (type == Operation.REQ) {
            log("RECEIVED_REQUEST", pid);
            if(requestQueue.isEmpty()){
                System.out.println("fila req vazia, tratando pid " + pid);
            }
            // Checa se está livre
            if (currentProcess == null) {
                // Se estiver livre, manda o grant e marca ocupado
                currentProcess = pid;
                sendGrant(pid);
            } else {
                System.out.println("RC ocupada, adicionando na fila.");
                // Se não estiver, adiciona na fila de requests (espera)
                requestQueue.add(pid);
                System.out.println("Fila atual: " + requestQueue);

                log("QUEUE_ADD", pid);
            }

        } else if (type == Operation.REL) {
            log("RECEIVED_RELEASE", pid);

            // Verifica se quem mandou o release é quem realmente estava lá
            if (currentProcess != null && currentProcess == pid) {
                currentProcess = null; // Libera a RC

                // Se a fila não estiver vazia (tem clientes esperando)
                if (!requestQueue.isEmpty()) {
                    int nextPid = requestQueue.poll(); // Tira da fila
                    System.out.println("REL recebido, retirando da fila.");
                    System.out.println("Fila atual: " + requestQueue);
                    currentProcess = nextPid; // Marca como novo ocupante
                    sendGrant(nextPid); // Dá o grant
                }
            } else {
                log("ERROR_RELEASE_INVALID_OWNER", pid);
            }
        } else {
            log("UNKNOWN_MSG_TYPE", pid);
        }

    }

    private void sendGrant(int pid) {
        Socket s = clientSockets.get(pid);
        if (s != null && !s.isClosed()) {
            try {
                sendMessage(s, buildMessage(Operation.GNT.getEnumerated(), pid));
                log("SENT_GRANT", pid);
            } catch (IOException e) {
                e.printStackTrace();
                log("ERROR_SENDING_GRANT", pid);
            }
        } else {
            log("ERROR_SOCKET_NOT_FOUND", pid);
        }
    }

    private Thread getUIThread(){
        return new Thread(() -> {
            try (BufferedReader console = new BufferedReader(new InputStreamReader(System.in))) {
                String line;
                printUiHelp();
                while ((line = console.readLine()) != null) {
                    line = line.trim();
                    if (line.equalsIgnoreCase("1")) {
                        // Sincroniza para leitura segura
                        synchronized (algorithmLock) {
                            System.out.println("Ocupante Atual: " + (currentProcess == null ? "[Ninguém]" : currentProcess));
                            System.out.println("Fila de Espera: " + this.requestQueue);
                        }
                    } else if (line.equalsIgnoreCase("2")) {
                        printServiceCounts();
                    } else if (line.equalsIgnoreCase("3")) {
                        System.exit(0);
                    } else if (line.equalsIgnoreCase("h")) {
                        printUiHelp();
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

    public static void main(String[] args) throws Exception {
        int port = 5000;
        if (args.length >= 1) port = Integer.parseInt(args[0]);
        Coordinator c = new Coordinator(port);
        c.start();
    }
}

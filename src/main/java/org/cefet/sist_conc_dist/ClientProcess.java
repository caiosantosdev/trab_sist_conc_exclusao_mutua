package org.cefet.sist_conc_dist;// ClientProcess.java
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

public class ClientProcess {
    private static final int REQ = 1;
    private static final int GNT = 2;
    private static final int REL = 3;

    private static final int F = 64; // same fixed length
    private final int pid;
    private final String host;
    private final int port;
    private final int r; // repetitions
    private final int k; // sleep seconds in critical section

    public ClientProcess(int pid, String host, int port, int r, int k) {
        this.pid = pid;
        this.host = host;
        this.port = port;
        this.r = r;
        this.k = k;
    }

    public void start() throws IOException {
        Socket socket = new Socket(host, port);
        System.out.println("Process " + pid + " connected to coordinator.");

        // reader thread to receive GRANT messages
        final Object grantLock = new Object();
        final boolean[] granted = {false};

        Thread reader = new Thread(() -> {
            try (InputStream in = socket.getInputStream()) {
                byte[] buf = new byte[F];
                while (true) {
                    int read = 0;
                    while (read < F) {
                        int r = in.read(buf, read, F - read);
                        if (r == -1) throw new EOFException("server closed");
                        read += r;
                    }
                    String msg = new String(buf, StandardCharsets.UTF_8).trim();
                    int type = parseType(msg);
                    if (type == GNT) {
                        // explicar
                        synchronized (grantLock) {
                            granted[0] = true;
                            grantLock.notify();
                        }
                    } else {
                        // ignore other types
                    }
                }
            } catch (Exception e) {
                // server might close at end
                // e.printStackTrace();
            }
        }, "reader-" + pid);
        reader.setDaemon(true); // explicar
        reader.start();

        OutputStream out = socket.getOutputStream();

        for (int i = 0; i < r; i++) {
            // send REQUEST
            sendMessage(out, buildMessage(REQ, pid));
            System.out.println("pid " + pid + " sent REQUEST (" + (i+1) + "/" + r + ")");
            // wait for GRANT
            synchronized (grantLock) {
                while (!granted[0]) {
                    try {
                        grantLock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                granted[0] = false; // reset
            }
            System.out.println("pid " + pid + " received GRANT, entering critical section.");
            // write to resultado.txt
            writeResult(); // explicar
            // sleep k seconds
            try { Thread.sleep(k * 1000L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            // send RELEASE
            sendMessage(out, buildMessage(REL, pid));
            System.out.println("pid " + pid + " sent RELEASE (" + (i+1) + "/" + r + ")");
        }

        System.out.println("pid " + pid + " finished all repetitions. Exiting.");
        socket.close();
    }

    private void writeResult() {
        String time = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        String line = pid + "," + time;
        try (FileWriter fw = new FileWriter("resultado.txt", true)) {
            fw.write(line + System.lineSeparator());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private int parseType(String msg) {
        String[] parts = msg.split("\\|");
        try {
            return Integer.parseInt(parts[0]);
        } catch (Exception e) {
            return -1;
        }
    }

    private void sendMessage(OutputStream out, String msg) throws IOException {
        out.write(msg.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private String buildMessage(int type, int pid) {
        String base = type + "|" + pid + "|";
        StringBuilder sb = new StringBuilder(base);
        while (sb.toString().getBytes(StandardCharsets.UTF_8).length < F) sb.append('0');
        return sb.toString().substring(0, F);
    }

    public static void main(String[] args) throws Exception {
        // args: pid host port r k
        if (args.length < 5) {
            System.out.println("Usage: java ClientProcess <pid> <host> <port> <r> <k>");
            return;
        }
        int pid = Integer.parseInt(args[0]);
        String host = args[1];
        int port = Integer.parseInt(args[2]);
        int r = Integer.parseInt(args[3]);
        int k = Integer.parseInt(args[4]);
        ClientProcess cp = new ClientProcess(pid, host, port, r, k);
        cp.start();
    }
}

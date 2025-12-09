    package org.cefet.sist_conc_dist;// ClientProcess.java
    import org.cefet.sist_conc_dist.enums.Operation;

    import java.io.*;
    import java.net.*;
    import java.nio.charset.StandardCharsets;
    import java.time.Instant;
    import java.time.format.DateTimeFormatter;
    import java.util.Random;

    public class ClientProcess extends BaseProcess{
        private final int pid;
        private final String host;
        private final int repeticoes; // repetitions

        public ClientProcess(int pid, String host, int port, int repeticoes) {
            this.pid = pid;
            this.host = host;
            this.port = port;
            this.repeticoes = repeticoes;
        }

        public void start() throws IOException {
            Socket socket = new Socket(host, port);
            System.out.println("Process " + pid + " conectado com o coordinator.");

            final Object grantLock = new Object();
            final boolean[] granted = {false};

            Thread reader = getReaderThread(socket, grantLock, granted);
            reader.start();

            OutputStream out = socket.getOutputStream();

            for (int i = 0; i < repeticoes; i++) {

                double randomico = Math.random();
                System.out.println("randomico: " + randomico);
                if(randomico < 0.7){

                    Random random = new Random();
                    try {
                        int segundos = random.nextInt(3, 5);
                        System.out.println("Thread " + this.pid + " dormindo por " +  segundos + " segundos.");
                        Thread.sleep( segundos * 1000L );
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                } else {
                    System.out.println("Thread " + this.pid + " não dormiu.");
                }
                sendMessage(out, buildMessage(Operation.REQ.getEnumerated(), pid));
                System.out.println("pid " + pid + " mandou REQUEST (" + (i+1) + "/" + repeticoes + ")");


                synchronized (grantLock) {
                    while (!granted[0]) {
                        try {
                            grantLock.wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    granted[0] = false;
                }

                System.out.println("pid " + pid + " recebeu GRANT, entrando na região critica.");

                int segundos = new Random().nextInt(1, 3);
                System.out.println("Thread " + this.pid + " escrevendo na RC por " +  segundos + " segundos.");
                try {
                    Thread.sleep( segundos * 1000L );
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                writeResult();

                sendMessage(out, buildMessage(Operation.REL.getEnumerated(), pid));
                System.out.println("pid " + pid + " envia RELEASE (" + (i+1) + "/" + repeticoes + ")");
            }

            try { reader.join(); } catch (InterruptedException ignored) {}

            socket.close();
        }

        private Thread getReaderThread(Socket socket, Object grantLock, boolean[] granted) {
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
                        Operation type = Operation.fromNumber(parseType(msg));
                        if (type == Operation.GNT) {
                            synchronized (grantLock) {
                                granted[0] = true;
                                grantLock.notify();
                            }
                        }
                    }
                } catch (Exception e) {
                    // e.printStackTrace();
                }
            }, "reader-" + pid);
            reader.setDaemon(true);
            return reader;
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
            // pid -> numero que vai identificar o processo
            // host -> host do coordinator (localhost normalmente) para conexão do socket
            // port -> porta do coordinator para conexão do socket
            // r -> numeroRepeticoes
            // k -> segundos dormindo

            if (args.length < 4) {
                System.out.println("Usage: java ClientProcess <pid> <host> <port> <numeroRepeticoes>");
                return;
            }
            int pid = Integer.parseInt(args[0]);
            String host = args[1];
            int port = Integer.parseInt(args[2]);
            int numRepeticoes = Integer.parseInt(args[3]);

            ClientProcess cp = new ClientProcess(pid, host, port, numRepeticoes);
            cp.start();
        }
    }

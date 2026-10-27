package dev.oleander.app;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Phone-to-phone link cable: carries the web app's link messages (one JSON
 * object per line, the same messages the relay server forwards) between
 * consoles over TCP (Wi-Fi, hotspot) or Bluetooth.
 *
 * The hosting phone acts as the relay: every line it receives from one
 * console is passed to its own page and to all other consoles. Plain Java
 * (no Android APIs) so it can be tested on any JVM; Bluetooth sockets are
 * plugged in through {@link Acceptor} and {@link #addPeer}.
 */
final class LinkHub {
    static final int TCP_PORT = 8766;
    static final int MAX_PEERS = 3;

    interface Listener {
        /** A message for this console's page. */
        void onMessage(String line);
        /** "hosting", "connected", "peer-joined", "peer-left", "closed" or "error". */
        void onStatus(String type, String detail);
    }

    /** Accepts incoming connections (TCP server socket, Bluetooth server socket). */
    interface Acceptor extends Closeable {
        Peer accept() throws IOException;
    }

    static final class Peer {
        final InputStream in;
        final OutputStream out;
        final Closeable conn;
        final String name;
        final LinkedBlockingQueue<String> outbox = new LinkedBlockingQueue<>();
        volatile boolean open = true;

        Peer(InputStream in, OutputStream out, Closeable conn, String name) {
            this.in = in;
            this.out = out;
            this.conn = conn;
            this.name = name;
        }
    }

    private static final String CLOSE = "\u0000close";

    private final Listener listener;
    private final List<Peer> peers = new CopyOnWriteArrayList<>();
    private volatile Acceptor acceptor;
    private volatile boolean hosting;
    private volatile boolean closed;

    LinkHub(Listener listener) {
        this.listener = listener;
    }

    boolean isHosting() {
        return hosting;
    }

    int peerCount() {
        return peers.size();
    }

    /** Starts accepting consoles on a background thread. */
    void host(Acceptor a, String description) {
        hosting = true;
        acceptor = a;
        listener.onStatus("hosting", description);
        Thread t = new Thread(() -> {
            while (!closed) {
                Peer p;
                try {
                    p = a.accept();
                } catch (IOException e) {
                    if (!closed) listener.onStatus("error", "Stopped accepting connections: " + e.getMessage());
                    return;
                }
                if (peers.size() >= MAX_PEERS) {
                    closeQuietly(p.conn);
                    continue;
                }
                addPeer(p);
                listener.onStatus("peer-joined", p.name);
            }
        }, "link-accept");
        t.setDaemon(true);
        t.start();
    }

    /** Hosts on a TCP port (0 = any); returns the bound port. */
    int hostTcp(int port) throws IOException {
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(port));
        host(new Acceptor() {
            @Override
            public Peer accept() throws IOException {
                Socket s = ss.accept();
                s.setTcpNoDelay(true);
                return new Peer(s.getInputStream(), s.getOutputStream(), s, s.getInetAddress().getHostAddress());
            }

            @Override
            public void close() throws IOException {
                ss.close();
            }
        }, String.join(", ", localAddresses()) + " (port " + ss.getLocalPort() + ")");
        return ss.getLocalPort();
    }

    /** Connects to a hosting phone; blocks, so call it off the UI thread. */
    void joinTcp(String host, int port) throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 8000);
        s.setTcpNoDelay(true);
        addPeer(new Peer(s.getInputStream(), s.getOutputStream(), s, host));
        listener.onStatus("connected", host);
    }

    /** Registers a connected console and starts its reader and writer threads. */
    void addPeer(Peer p) {
        peers.add(p);
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isEmpty()) continue;
                    listener.onMessage(line);
                    if (hosting) for (Peer o : peers) if (o != p) o.outbox.offer(line);
                }
            } catch (IOException ignored) {
                // connection lost
            }
            drop(p);
        }, "link-read");
        Thread writer = new Thread(() -> {
            try {
                Writer w = new OutputStreamWriter(p.out, StandardCharsets.UTF_8);
                while (p.open) {
                    String line = p.outbox.take();
                    if (line == CLOSE) break;
                    w.write(line);
                    w.write('\n');
                    // Send everything queued in one go.
                    String more;
                    while ((more = p.outbox.poll()) != null && more != CLOSE) {
                        w.write(more);
                        w.write('\n');
                    }
                    w.flush();
                    if (more == CLOSE) break;
                }
            } catch (IOException | InterruptedException ignored) {
                // connection lost or closing
            }
            drop(p);
        }, "link-write");
        reader.setDaemon(true);
        writer.setDaemon(true);
        reader.start();
        writer.start();
    }

    /** Sends a line from this console's page to every other console. */
    void send(String line) {
        for (Peer p : peers) p.outbox.offer(line);
    }

    private void drop(Peer p) {
        if (!peers.remove(p)) return;
        p.open = false;
        p.outbox.offer(CLOSE);
        closeQuietly(p.conn);
        if (closed) return;
        if (hosting) listener.onStatus("peer-left", p.name);
        else listener.onStatus("closed", "The host closed the link");
    }

    void close() {
        closed = true;
        closeQuietly(acceptor);
        for (Peer p : peers) {
            p.open = false;
            p.outbox.offer(CLOSE);
            closeQuietly(p.conn);
        }
        peers.clear();
    }

    static void closeQuietly(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    /** IPv4 addresses of this device (Wi-Fi, hotspot, Ethernet). */
    static List<String> localAddresses() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) out.add(a.getHostAddress());
                }
            }
        } catch (IOException ignored) {
            // no network
        }
        if (out.isEmpty()) out.add("no network");
        return out;
    }
}

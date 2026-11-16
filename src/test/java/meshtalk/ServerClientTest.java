package meshtalk;

import meshtalk.client.TcpClient;
import meshtalk.common.Frame;
import meshtalk.common.Protocol;
import meshtalk.server.MeshTalkServer;
import meshtalk.server.ServerMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class ServerClientTest {

    private int testPort;
    private MeshTalkServer server;

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @BeforeEach
    public void setUp() throws Exception {
        testPort = findFreePort();
        server = new MeshTalkServer(testPort, ServerMode.THREAD_POOL, 16);
        server.start();
        Thread.sleep(100);
    }

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    public void testUniqueLoginAndDuplicateRejection() throws Exception {
        TcpClient client1 = new TcpClient("localhost", testPort);
        assertTrue(client1.connectAndLogin("UserOne"));

        TcpClient client2 = new TcpClient("localhost", testPort);
        assertFalse(client2.connectAndLogin("UserOne"), "Duplicate username must be rejected");

        client1.disconnect();
        client2.disconnect();
    }

    @Test
    public void testBroadcastAndPrivateMessaging() throws Exception {
        CountDownLatch broadcastLatch = new CountDownLatch(1);
        CountDownLatch privateLatch = new CountDownLatch(1);
        AtomicBoolean gotBroadcast = new AtomicBoolean(false);
        AtomicBoolean gotPrivate = new AtomicBoolean(false);

        TcpClient alice = new TcpClient("localhost", testPort);
        TcpClient bob = new TcpClient("localhost", testPort);

        bob.setMessageListener(new SimpleListener() {
            @Override
            public void onMessage(String sender, String text) {
                if ("Alice".equals(sender) && "Hello World".equals(text)) {
                    gotBroadcast.set(true);
                    broadcastLatch.countDown();
                }
            }

            @Override
            public void onPrivateMessage(String sender, String text) {
                if ("Alice".equals(sender) && "Secret Msg".equals(text)) {
                    gotPrivate.set(true);
                    privateLatch.countDown();
                }
            }
        });

        assertTrue(alice.connectAndLogin("Alice"));
        assertTrue(bob.connectAndLogin("Bob"));
        Thread.sleep(100);

        alice.sendMessage("Hello World");
        assertTrue(broadcastLatch.await(3, TimeUnit.SECONDS), "Bob should receive Alice's broadcast");
        assertTrue(gotBroadcast.get());

        alice.sendPrivateMessage("Bob", "Secret Msg");
        assertTrue(privateLatch.await(3, TimeUnit.SECONDS), "Bob should receive Alice's private message");
        assertTrue(gotPrivate.get());

        alice.disconnect();
        bob.disconnect();
    }

    @Test
    public void testTcpFileTransferWithSha256(@TempDir Path tempDir) throws Exception {
        CountDownLatch fileCompleteLatch = new CountDownLatch(1);
        AtomicBoolean fileVerified = new AtomicBoolean(false);

        File testFile = tempDir.resolve("test_transfer.bin").toFile();
        byte[] data = new byte[8192];
        new java.util.Random().nextBytes(data);
        Files.write(testFile.toPath(), data);

        TcpClient sender = new TcpClient("localhost", testPort);
        TcpClient receiver = new TcpClient("localhost", testPort);

        receiver.setMessageListener(new SimpleListener() {
            @Override
            public void onFileComplete(String filename, boolean verified) {
                fileVerified.set(verified);
                fileCompleteLatch.countDown();
            }
        });

        assertTrue(sender.connectAndLogin("FileSender"));
        assertTrue(receiver.connectAndLogin("FileReceiver"));
        Thread.sleep(100);

        sender.sendFile("FileReceiver", testFile);
        assertTrue(fileCompleteLatch.await(5, TimeUnit.SECONDS), "File transfer should complete");
        assertTrue(fileVerified.get(), "SHA-256 of received file must match original");

        sender.disconnect();
        receiver.disconnect();
    }

    @Test
    public void testHighConcurrentClients() throws Exception {
        // Stop the default 16-thread server and run one with 32 threads for 25 concurrent persistent connections
        server.stop();
        server = new MeshTalkServer(testPort, ServerMode.THREAD_POOL, 32);
        server.start();
        Thread.sleep(100);

        int clientCount = 25;
        List<TcpClient> clients = new ArrayList<>();

        for (int i = 0; i < clientCount; i++) {
            TcpClient c = new TcpClient("localhost", testPort);
            assertTrue(c.connectAndLogin("Client_" + i), "Client " + i + " must connect and login");
            clients.add(c);
        }

        assertEquals(clientCount, clients.size(), "All concurrent clients should be connected");
        assertEquals(clientCount, server.getMetrics().getConnectedClients());

        for (TcpClient c : clients) {
            c.disconnect();
        }
        Thread.sleep(300);
        assertEquals(0, server.getMetrics().getConnectedClients(), "All clients should be cleaned up after disconnect");
    }

    @Test
    public void testMalformedAndOversizedFrames() throws Exception {
        try (Socket rawSock = new Socket("localhost", testPort);
             DataOutputStream out = Frame.outputStream(rawSock);
             DataInputStream in = Frame.inputStream(rawSock)) {

            // First frame not LOGIN: send MSG directly
            Frame.write(out, new Frame(Protocol.MSG, "Sneaky message without login"));

            Frame resp = Frame.read(in);
            assertEquals(Protocol.LOGIN_FAIL, resp.getType(), "Server must reject frames before LOGIN");
        }
    }

    private static class SimpleListener implements TcpClient.MessageListener {
        @Override public void onMessage(String sender, String text) {}
        @Override public void onPrivateMessage(String sender, String text) {}
        @Override public void onUserJoined(String user) {}
        @Override public void onUserLeft(String user) {}
        @Override public void onUserList(List<String> users) {}
        @Override public void onHistory(List<String> history) {}
        @Override public void onFileOffer(String sender, String filename, long size) {}
        @Override public void onFileProgress(String filename, double progress) {}
        @Override public void onFileComplete(String filename, boolean verified) {}
        @Override public void onRoomMessage(String room, String sender, String text) {}
        @Override public void onError(String error) {}
        @Override public void onDisconnect() {}
    }
}

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

// TunnelClient.java - Run this on your local machine
public class TunnelClient {
    private static final String SERVER_HOST = "13.203.214.112"; // e.g., "3.15.123.45"
    private static final int TUNNEL_PORT = 9000;
    private static final String LOCAL_HOST = "localhost";
    private static final int LOCAL_PORT = 8080; // Your local webapp port
    private static String TUNNEL_ID = "myapp"; // Choose a custom ID

    public static void main(String[] args) throws IOException {
        // Allow custom tunnel ID from command line
        if (args.length > 0) {
            TUNNEL_ID = args[0];
        }
        if (args.length > 1) {
            try {
                int port = Integer.parseInt(args[1]);
                System.setProperty("LOCAL_PORT", String.valueOf(port));
            } catch (NumberFormatException e) {
                System.err.println("Invalid port number");
            }
        }

        System.out.println("=== Tunnel Client ===");
        System.out.println("Tunnel ID: " + TUNNEL_ID);
        System.out.println("Local: http://" + LOCAL_HOST + ":" + LOCAL_PORT);
        System.out.println("Connecting to: " + SERVER_HOST + ":" + TUNNEL_PORT);
        
        connectAndServe();
    }

    private static void connectAndServe() {
        while (true) {
            try {
                System.out.println("\nConnecting to tunnel server...");
                Socket tunnelSocket = new Socket(SERVER_HOST, TUNNEL_PORT);
                tunnelSocket.setKeepAlive(true);
                
                // Register tunnel ID
                PrintWriter out = new PrintWriter(tunnelSocket.getOutputStream(), true);
                out.println(TUNNEL_ID);
                
                System.out.println("✓ Tunnel established!");
                System.out.println("✓ Your webapp is now public at:");
                System.out.println("  http://" + SERVER_HOST + "/" + TUNNEL_ID);
                System.out.println("\nWaiting for requests...\n");
                
                // Handle incoming requests
                while (!tunnelSocket.isClosed()) {
                    try {
                        handleIncomingRequest(tunnelSocket);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                
            } catch (Exception e) {
                System.err.println("✗ Connection error: " + e.getMessage());
                System.out.println("Reconnecting in 5 seconds...");
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }
    }

    private static void handleIncomingRequest(Socket tunnelSocket) throws IOException, InterruptedException {
        InputStream serverIn = tunnelSocket.getInputStream();
        
        if (serverIn.available() > 0) {
            // Read request from tunnel server
            ByteArrayOutputStream requestBuffer = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int bytesRead;
            
            while (serverIn.available() > 0 && (bytesRead = serverIn.read(buffer)) != -1) {
                requestBuffer.write(buffer, 0, bytesRead);
                if (serverIn.available() == 0) break;
            }

            byte[] requestData = requestBuffer.toByteArray();
            if (requestData.length == 0) return;

            System.out.println("→ Forwarding request to local service (" + requestData.length + " bytes)");

            // Forward to local webapp
            try (Socket localSocket = new Socket(LOCAL_HOST, LOCAL_PORT)) {
                OutputStream localOut = localSocket.getOutputStream();
                localOut.write(requestData);
                localOut.flush();

                // Read response from local webapp
                InputStream localIn = localSocket.getInputStream();
                OutputStream serverOut = tunnelSocket.getOutputStream();
                
                int totalBytes = 0;
                while ((bytesRead = localIn.read(buffer)) != -1) {
                    serverOut.write(buffer, 0, bytesRead);
                    totalBytes += bytesRead;
                    if (localIn.available() == 0) break;
                }
                
                serverOut.flush();
                System.out.println("Sent response (" + totalBytes + " bytes)");
                
            } catch (ConnectException e) {
                System.err.println("✗ Cannot connect to local service on port " + LOCAL_PORT);
                System.err.println("  Make sure your webapp is running!");
                
                // Send error response back through tunnel
                String errorResponse = "HTTP/1.1 502 Bad Gateway\r\n" +
                    "Content-Type: text/html\r\n\r\n" +
                    "<h1>502 Bad Gateway</h1>" +
                    "<p>Cannot connect to local service on port " + LOCAL_PORT + "</p>";
                tunnelSocket.getOutputStream().write(errorResponse.getBytes());
                tunnelSocket.getOutputStream().flush();
            }
        } else {
            Thread.sleep(50); // Small delay to prevent CPU spinning
        }
    }
}
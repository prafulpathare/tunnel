import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class TunnelServer {
    private static final int PUBLIC_PORT = 80;
    private static final int TUNNEL_PORT = 9000;
    private static final Map<String, TunnelConnection> tunnelClients = new ConcurrentHashMap<>();
    private static final ExecutorService executor = Executors.newCachedThreadPool();

    static class TunnelConnection {
        Socket socket;
        long lastActivity;
        
        TunnelConnection(Socket socket) {
            this.socket = socket;
            this.lastActivity = System.currentTimeMillis();
        }
    }

    public static void main(String[] args) throws IOException {
        System.out.println("=== Tunnel Server Starting ===");
        System.out.println("Public HTTP Port: " + PUBLIC_PORT);
        System.out.println("Tunnel Port: " + TUNNEL_PORT);
        
        // Cleanup dead connections periodically
        new Thread(() -> cleanupDeadConnections()).start();
        
        // Accept tunnel client connections
        new Thread(() -> acceptTunnelConnections()).start();
        
        // Start public HTTP server
        startPublicServer();
    }

    private static void cleanupDeadConnections() {
        while (true) {
            try {
                Thread.sleep(30000); // Check every 30 seconds
                long now = System.currentTimeMillis();
                
                tunnelClients.entrySet().removeIf(entry -> {
                    TunnelConnection conn = entry.getValue();
                    if (conn.socket.isClosed() || now - conn.lastActivity > 300000) { // 5 min timeout
                        System.out.println("Removing dead tunnel: " + entry.getKey());
                        try { conn.socket.close(); } catch (IOException e) {}
                        return true;
                    }
                    return false;
                });
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private static void acceptTunnelConnections() {
        try (ServerSocket tunnelSocket = new ServerSocket(TUNNEL_PORT)) {
            System.out.println("✓ Tunnel listener ready on port " + TUNNEL_PORT);
            
            while (true) {
                try {
                    Socket client = tunnelSocket.accept();
                    client.setKeepAlive(true);
                    client.setSoTimeout(300000); // 5 min timeout
                    
                    BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                    String tunnelId = in.readLine();
                    
                    if (tunnelId != null && !tunnelId.isEmpty()) {
                        tunnelClients.put(tunnelId, new TunnelConnection(client));
                        System.out.println("✓ Tunnel registered: " + tunnelId + " from " + client.getInetAddress());
                        System.out.println("  Public URL: http://<EC2-IP>/" + tunnelId);
                    }
                } catch (IOException e) {
                    System.err.println("Error accepting tunnel connection: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Fatal error on tunnel port: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void startPublicServer() throws IOException {
        ServerSocket publicSocket = new ServerSocket(PUBLIC_PORT);
        System.out.println("✓ Public HTTP server ready on port " + PUBLIC_PORT);
        System.out.println("\n=== Server Ready ===\n");
        
        while (true) {
            try {
                Socket clientSocket = publicSocket.accept();
                clientSocket.setSoTimeout(30000); // 30 sec timeout
                executor.submit(() -> handlePublicRequest(clientSocket));
            } catch (IOException e) {
                System.err.println("Error accepting public connection: " + e.getMessage());
            }
        }
    }

    private static void handlePublicRequest(Socket clientSocket) {
        try {
            InputStream clientIn = clientSocket.getInputStream();
            ByteArrayOutputStream requestBuffer = new ByteArrayOutputStream();
            
            // Read the entire HTTP request
            byte[] buffer = new byte[8192];
            int bytesRead;
            int contentLength = 0;
            boolean headersComplete = false;
            StringBuilder headers = new StringBuilder();
            
            while ((bytesRead = clientIn.read(buffer)) != -1) {
                requestBuffer.write(buffer, 0, bytesRead);
                
                if (!headersComplete) {
                    String data = new String(requestBuffer.toByteArray());
                    int headerEnd = data.indexOf("\r\n\r\n");
                    
                    if (headerEnd != -1) {
                        headersComplete = true;
                        headers.append(data.substring(0, headerEnd));
                        
                        // Parse Content-Length
                        for (String line : headers.toString().split("\r\n")) {
                            if (line.toLowerCase().startsWith("content-length:")) {
                                contentLength = Integer.parseInt(line.substring(15).trim());
                            }
                        }
                        
                        // Check if we have the full body
                        int bodyStart = headerEnd + 4;
                        int bodyReceived = requestBuffer.size() - bodyStart;
                        if (bodyReceived >= contentLength) {
                            break;
                        }
                    }
                } else {
                    break;
                }
                
                if (clientIn.available() == 0) break;
            }

            byte[] requestData = requestBuffer.toByteArray();
            String requestStr = new String(requestData);
            
            // Extract tunnel ID from path
            String tunnelId = extractTunnelId(requestStr);
            
            if (tunnelId == null) {
                sendResponse(clientSocket, "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n" +
                    "<h1>Tunnel Server</h1><p>Append your tunnel ID to the URL: http://&lt;server-ip&gt;/&lt;tunnel-id&gt;</p>");
                return;
            }

            TunnelConnection tunnel = tunnelClients.get(tunnelId);
            
            if (tunnel == null || tunnel.socket.isClosed()) {
                sendResponse(clientSocket, "HTTP/1.1 502 Bad Gateway\r\nContent-Type: text/html\r\n\r\n" +
                    "<h1>502 Bad Gateway</h1><p>Tunnel '" + tunnelId + "' not connected</p>");
                return;
            }

            // Update activity timestamp
            tunnel.lastActivity = System.currentTimeMillis();

            // Rewrite the path to remove tunnel ID
            String modifiedRequest = rewriteRequest(requestStr, tunnelId);

            // Forward request to tunnel client
            OutputStream tunnelOut = tunnel.socket.getOutputStream();
            tunnelOut.write(modifiedRequest.getBytes());
            tunnelOut.flush();

            // Read response from tunnel and forward to client
            InputStream tunnelIn = tunnel.socket.getInputStream();
            OutputStream clientOut = clientSocket.getOutputStream();
            
            long startTime = System.currentTimeMillis();
            while ((bytesRead = tunnelIn.read(buffer)) != -1) {
                clientOut.write(buffer, 0, bytesRead);
                if (tunnelIn.available() == 0 || System.currentTimeMillis() - startTime > 30000) {
                    break;
                }
            }
            
            clientOut.flush();
            
        } catch (Exception e) {
            System.err.println("Error handling request: " + e.getMessage());
        } finally {
            try { clientSocket.close(); } catch (IOException e) {}
        }
    }

    private static String extractTunnelId(String request) {
        String[] lines = request.split("\r\n");
        if (lines.length == 0) return null;
        
        String[] parts = lines[0].split(" ");
        if (parts.length < 2) return null;
        
        String path = parts[1];
        if (path.startsWith("/") && path.length() > 1) {
            String[] pathParts = path.substring(1).split("/");
            return pathParts[0];
        }
        
        return null;
    }

    private static String rewriteRequest(String request, String tunnelId) {
        String[] lines = request.split("\r\n", 2);
        if (lines.length < 2) return request;
        
        String requestLine = lines[0];
        String[] parts = requestLine.split(" ");
        
        if (parts.length >= 2) {
            String path = parts[1];
            // Remove tunnel ID from path
            if (path.startsWith("/" + tunnelId)) {
                path = path.substring(tunnelId.length() + 1);
                if (path.isEmpty()) path = "/";
            }
            parts[1] = path;
            requestLine = String.join(" ", parts);
        }
        
        return requestLine + "\r\n" + lines[1];
    }

    private static void sendResponse(Socket socket, String response) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(response.getBytes());
        out.flush();
        socket.close();
    }
}

// TunnelClient.java - Run this on your local machine
class TunnelClient {
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
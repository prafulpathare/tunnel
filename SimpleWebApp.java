import java.io.*;
import java.net.*;
import java.util.*;
import java.text.SimpleDateFormat;

public class SimpleWebApp {
    public static void main(String[] args) throws IOException {
        ServerSocket serverSocket = new ServerSocket(8080);
        System.out.println("Server started on http://localhost:8080");
        System.out.println("Press Ctrl+C to stop the server");
        
        while (true) {
            Socket clientSocket = serverSocket.accept();
            handleRequest(clientSocket);
        }
    }
    
    private static void handleRequest(Socket socket) {
        try (
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter out = new PrintWriter(socket.getOutputStream());
            BufferedOutputStream dataOut = new BufferedOutputStream(socket.getOutputStream())
        ) {
            String requestLine = in.readLine();
            if (requestLine == null) return;
            
            String[] tokens = requestLine.split(" ");
            String method = tokens[0];
            String path = tokens[1];
            
            // Read headers
            while (true) {
                String line = in.readLine();
                if (line == null || line.isEmpty()) break;
            }
            
            // Generate HTML response
            String html = generateHTML(path);
            
            // Send HTTP response
            out.println("HTTP/1.1 200 OK");
            out.println("Content-Type: text/html");
            out.println("Content-Length: " + html.length());
            out.println();
            out.flush();
            
            dataOut.write(html.getBytes());
            dataOut.flush();
            
        } catch (IOException e) {
            System.err.println("Error handling request: " + e.getMessage());
        }
    }
    
    private static String generateHTML(String path) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String currentTime = sdf.format(new Date());
        
        return "<!DOCTYPE html>" +
               "<html lang='en'>" +
               "<head>" +
               "<meta charset='UTF-8'>" +
               "<meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
               "<title>Simple Java Web App</title>" +
               "<style>" +
               "body { font-family: Arial, sans-serif; max-width: 800px; margin: 50px auto; padding: 20px; background: #f5f5f5; }" +
               ".container { background: white; padding: 30px; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }" +
               "h1 { color: #333; border-bottom: 3px solid #4CAF50; padding-bottom: 10px; }" +
               ".info { background: #e8f5e9; padding: 15px; border-radius: 5px; margin: 20px 0; }" +
               ".info p { margin: 8px 0; }" +
               ".label { font-weight: bold; color: #2e7d32; }" +
               "a { color: #4CAF50; text-decoration: none; }" +
               "a:hover { text-decoration: underline; }" +
               "</style>" +
               "</head>" +
               "<body>" +
               "<div class='container'>" +
               "<h1>🚀 Simple Java Web Server</h1>" +
               "<div class='info'>" +
               "<p><span class='label'>Current Path:</span> " + path + "</p>" +
               "<p><span class='label'>Server Time:</span> " + currentTime + "</p>" +
               "<p><span class='label'>Status:</span> Running on port 8080</p>" +
               "</div>" +
               "<p>This is a minimal web server built with pure Java using ServerSocket.</p>" +
               "<p>Try visiting: <a href='/about'>/about</a> | <a href='/hello'>/hello</a> | <a href='/test'>/test</a></p>" +
               "</div>" +
               "</body>" +
               "</html>";
    }
}
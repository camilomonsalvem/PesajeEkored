package de.kai_morich.simple_usb_terminal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;

import android.provider.Settings;
import android.util.Log;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ForegroundService extends Service {

    private static final String TAG = "ForegroundService";
    private NotificationManager notificationManager;
    private final Handler handler = new Handler();
    private static final int ONGOING_NOTIFICATION_ID = 101;
    private static final String CHANNEL_ID = "1001";
    private final ExecutorService executorService = Executors.newFixedThreadPool(4);

    private ServerSocket serverSocket;

    // Define commands and corresponding patterns
    private static final Map<String, Pattern[]> COMMAND_PATTERNS = new HashMap<>();

    static {
        COMMAND_PATTERNS.put("$", new Pattern[]{
                Pattern.compile("=c?\\d{4}\\.\\d"),         // Pattern 1: =cXXXX.X
                Pattern.compile("\\+\\s*\\d{1,5}\\.\\dkg"), // Pattern 2: +XXXXX.Xkg (with optional spaces)
                Pattern.compile("ST,GS,\\+\\d{5}\\.\\dkg")  // Pattern 3: ST,GS,+XXXXX.Xkg
        });
        COMMAND_PATTERNS.put("R", new Pattern[]{
                Pattern.compile("ST,GS,\\+\\d{5}\\.\\dkg")  // Example pattern for command "R"
        });
        // Add more commands and patterns as needed
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        startServer();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopServer();
        executorService.shutdown();
        Log.d(TAG, "Enter onDestroy");
        try {
            Log.d(TAG, "Try");
            if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
                Log.d(TAG, "If");
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "Error: ", e);
            executorService.shutdownNow();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    private void makeForeground(String data) {
        createServiceNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Aplicación Pesaje Ekored")
                .setContentText("La aplicación se encuentra en ejecución...")
                .setSmallIcon(R.drawable.ic_notification)
                .build();
        startForeground(ONGOING_NOTIFICATION_ID, notification);
    }

    private void createServiceNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Foreground Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        notificationManager.createNotificationChannel(channel);
    }

    private void startServer() {
        stopServer();
        new Thread(new HttpServerThread()).start();
    }

    private void stopServer() {
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing server socket", e);
            }
        }
    }

    private static final Set<String> ALLOWED_ORIGINS = new HashSet<>();

    static {
        ALLOWED_ORIGINS.add("http://localhost:5173");
        ALLOWED_ORIGINS.add("https://qa.ekored.site");
        ALLOWED_ORIGINS.add("https://ekored.site");
        ALLOWED_ORIGINS.add("https://siekored.enka.com.co");
        ALLOWED_ORIGINS.add("https://dllosiekored.enka.com.co");
        ALLOWED_ORIGINS.add("https://dlloekored.enka.com.co");
        ALLOWED_ORIGINS.add("https://dlloekored.enka.com.co:8081");
        ALLOWED_ORIGINS.add("http://127.0.0.1:5001");
        ALLOWED_ORIGINS.add("*");
    }

    private class HttpServerThread implements Runnable {
        @Override
        public void run() {
            try {
                serverSocket = new ServerSocket(5001, 0, InetAddress.getByName("localhost"));
                Log.i(TAG, "Server started on port 5001");
                while (!Thread.currentThread().isInterrupted()) {
                    Socket socket = serverSocket.accept();
                    executorService.submit(new HttpRequestHandler(socket));
                }
            } catch (IOException e) {
                Log.e(TAG, "Error starting server", e);
            }
        }
    }

    private class HttpRequestHandler implements Runnable {
        private final Socket socket;

        public HttpRequestHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try (InputStream inputStream = socket.getInputStream();
                 OutputStream outputStream = socket.getOutputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {

                String requestLine = reader.readLine();
                if (requestLine == null || requestLine.isEmpty()) {
                    return;
                }
                Log.d(TAG, "Request Line: " + requestLine);

                Map<String, String> headers = new HashMap<>();
                String line;
                while (!(line = reader.readLine()).isEmpty()) {
                    int colonIndex = line.indexOf(":");
                    if (colonIndex > 0) {
                        String headerName = line.substring(0, colonIndex).trim();
                        String headerValue = line.substring(colonIndex + 1).trim();
                        headers.put(headerName, headerValue);
                    }
                }

                String origin = headers.get("Origin");
                if (origin == null || !ALLOWED_ORIGINS.contains(origin)) {
                    sendResponse(outputStream, 403, "Forbidden", "text/plain");
                    return;
                }

                String[] requestParts = requestLine.split(" ");
                if (requestParts.length < 2) {
                    return;
                }

                String path = URLDecoder.decode(requestParts[1], "UTF-8");
                String[] pathParts = path.split("/");
                String endpoint = pathParts.length > 1 ? pathParts[1] : "";

                if ("device-name".equals(endpoint)) {
                    String deviceName = obtenerNombreDeDispositivo();
                    JSONObject jsonResponse = new JSONObject();
                    try {
                        jsonResponse.put("device_name", deviceName);
                    } catch (JSONException e) {
                        Log.e(TAG, "Error creating JSON response", e);
                    }
                    sendResponse(outputStream, 200, jsonResponse.toString(), "application/json");
                } else if ("leer-peso".equals(endpoint)) {
                    String puerto = getQueryParameter(path, "puerto");
                    int baudRate = Integer.parseInt(getQueryParameter(path, "baudRate", "9600"));
                    String paridad = getQueryParameter(path, "paridad", "N");
                    int bitDato = Integer.parseInt(getQueryParameter(path, "bitDeDato", "8"));
                    int bitParada = Integer.parseInt(getQueryParameter(path, "bitDeParada", "1"));
                    String comando = getQueryParameter(path, "comando", "$");

                    String data = leerPeso(puerto, baudRate, paridad, bitDato, bitParada, comando);
                    JSONObject jsonResponse = new JSONObject();
                    try {
                        jsonResponse.put("weight", data);
                    } catch (JSONException e) {
                        Log.e(TAG, "Error creating JSON response", e);
                    }
                    sendResponse(outputStream, 200, jsonResponse.toString(), "application/json");
                } else {
                    sendResponse(outputStream, 404, "Not Found", "text/plain");
                }

            } catch (IOException e) {
                Log.e(TAG, "Error handling request", e);
            } finally {
                try {
                    socket.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing socket", e);
                }
            }
        }

        private void sendResponse(OutputStream outputStream, int statusCode, String response, String contentType) throws IOException {
            String statusMessage = statusCode == 200 ? "OK" : statusCode == 403 ? "Forbidden" : "Not Found";
            String httpResponse = String.format(
                    "HTTP/1.1 %d %s\r\n" +
                            "Content-Type: %s\r\n" +
                            "Content-Length: %d\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                            "Access-Control-Allow-Headers: Origin, Content-Type, X-Auth-Token\r\n" +
                            "\r\n%s",
                    statusCode, statusMessage, contentType, response.length(), response
            );
            outputStream.write(httpResponse.getBytes());
            outputStream.flush();
        }

        private String getQueryParameter(String path, String parameter) {
            return getQueryParameter(path, parameter, null);
        }

        private String getQueryParameter(String path, String parameter, String defaultValue) {
            String[] queryParts = path.split("\\?");
            if (queryParts.length > 1) {
                String query = queryParts[1];
                String[] params = query.split("&");
                for (String param : params) {
                    String[] keyValue = param.split("=");
                    if (keyValue.length > 1 && keyValue[0].equals(parameter)) {
                        return keyValue[1];
                    }
                }
            }
            return defaultValue;
        }
    }

    private String leerPeso(String puerto, int baudRate, String paridad, int bitDato, int bitParada, String comando) {
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        UsbDevice device = null;

        for (UsbDevice v : usbManager.getDeviceList().values()) {
            device = v;
            break;  // Usa el primer dispositivo USB encontrado
        }

        if (device == null) {
            Log.e(TAG, "No USB device found");
            return "0.0";
        }

        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if (driver == null || driver.getPorts().isEmpty()) {
            Log.e(TAG, "No USB driver or ports found for the device");
            return "0.0";
        }

        UsbSerialPort usbSerialPort = driver.getPorts().get(0);
        UsbDeviceConnection usbConnection = usbManager.openDevice(driver.getDevice());
        if (usbConnection == null) {
            Log.e(TAG, "Opening USB connection failed");
            return "0.0";
        }

        try {
            usbSerialPort.open(usbConnection);
            usbSerialPort.setParameters(baudRate, bitDato, bitParada, UsbSerialPort.PARITY_NONE);

            // Send command to the scale
            usbSerialPort.write(comando.getBytes(), 1000);

            // Read data from the scale
            byte[] buffer = new byte[1028];
            int numBytesRead = usbSerialPort.read(buffer, 1000);
            if (numBytesRead > 0) {
                String data = new String(buffer, 0, numBytesRead);
                Log.d(TAG, "Received data: " + data);

                // Process buffer and extract weight data
                return processBuffer(data, comando);
            }

        } catch (IOException e) {
            Log.e(TAG, "Error setting up USB connection", e);
        } finally {
            try {
                usbSerialPort.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing USB connection", e);
            }
        }

        return "0.0";
    }

    private String processBuffer(String completeData, String command) {
        Log.d(TAG, "Complete data: " + completeData);

        // Get patterns for the specific command
        Pattern[] patterns = COMMAND_PATTERNS.get(command);

        if (patterns != null) {
            for (Pattern pattern : patterns) {
                Matcher matcher = pattern.matcher(completeData);
                if (matcher.find()) {
                    String matchedData = matcher.group();
                    Log.d(TAG, "Matched data: " + matchedData);

                    // Normalize matched data
                    String receivedData = matchedData
                            .replace("=", "")
                            .replace("c", "")
                            .replace("+", "")
                            .replace("kg", "")
                            .replace("ST,GS,", "")
                            .trim();

                    Log.d(TAG, "Processed weight data: " + receivedData);
                    return receivedData;
                }
            }
        }
        return "0.0";
    }

    public String obtenerNombreDeDispositivo() {
        String deviceName = "";
        Log.d(TAG, "Devicename: " + deviceName);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            deviceName = Settings.Global.getString(getContentResolver(), Settings.Global.DEVICE_NAME);
            Log.d(TAG, "Devicename: " + deviceName);
        }
        if (deviceName == null || deviceName.isEmpty()) {
            deviceName = Build.MODEL;
        }
        if (deviceName == null || deviceName.isEmpty()) {
            deviceName = "Unknown Device";
        }
        Log.d(TAG, "Devicename after if: " + deviceName);
        return deviceName;
    }

    // Add a static method to start the service
    public static void startService(Context context) {
        Intent intent = new Intent(context, ForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    // Add a static method to stop the service
    public static void stopService(Context context) {
        Intent intent = new Intent(context, ForegroundService.class);
        context.stopService(intent);
    }
}
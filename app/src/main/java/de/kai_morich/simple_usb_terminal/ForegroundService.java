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
import java.lang.reflect.Array;
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
import org.json.JSONArray;
import org.json.JSONException;

public class ForegroundService extends Service {

    private static final String TAG = "ForegroundService";
    private UsbSerialPort usbSerialPort;
    private String receivedData = "";
    private String matchData = "";
    private StringBuilder dataBuffer = new StringBuilder();
    private NotificationManager notificationManager;
    private boolean isStarted = false;
    private final Handler handler = new Handler();
    private final int updateInterval = 1000; // Intervalo en milisegundos para actualizar la notificación

    // Define commands and corresponding patterns
    private static final Map<String, Pattern[]> COMMAND_PATTERNS = new HashMap<>();

    static {
        COMMAND_PATTERNS.put("$", new Pattern[]{
                Pattern.compile("=c?\\d{4}\\.\\d"),         // Pattern 1: =cXXXX.X
                Pattern.compile("\\+\\s*\\d{1,5}\\.\\dkg"), // Pattern 2: +XXXXX.Xkg (with optional spaces)
                Pattern.compile("ST,GS,\\+\\d{5}\\.\\dkg")  // Pattern 3: ST,GS,+XXXXX.Xkg
        });
        COMMAND_PATTERNS.put("R", new Pattern[]{
                Pattern.compile("ST,GS,\\+\\d{5}\\.\\dkg"),  // Example pattern for command "R"
                Pattern.compile("=c?\\d{4}\\.\\d"),         // Pattern 1: =cXXXX.X
                Pattern.compile("\\+\\s*\\d{1,5}\\.\\dkg"), // Pattern 2: +XXXXX.Xkg (with optional spaces)
                Pattern.compile("ST,GS,\\+\\d{5}\\.\\dkg")  // Pattern 3: ST,GS,+XXXXX.Xkg
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
        isStarted = false;
        stopServer(); // Ensure the server is stopped when the activity is destroyed
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
        handler.removeCallbacks(updateNotificationTask); // Detener la actualización de la notificación
    }

    @Override
    public IBinder onBind(Intent intent) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isStarted) {
            makeForeground(obtenerDatosDeBascula());
            isStarted = true;
        }
        return START_STICKY;
    }

    private void makeForeground(String data) {
        createServiceNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Aplicación Pesaje Ekored")
                .setContentText("La aplicación se esta ejecutando...")
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

    private void updateNotification() {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Aplicación Pesaje Ekored")
                .setContentText("La aplicación se esta ejecutando...")
                .setSmallIcon(R.drawable.ic_notification)
                .build();
        notificationManager.notify(ONGOING_NOTIFICATION_ID, notification);
    }

    private final Runnable updateNotificationTask = new Runnable() {
        @Override
        public void run() {
            //String weightData = obtenerDatosDeBascula();
            //Log.d("", "weightData: " + weightData);
            updateNotification();
            handler.postDelayed(this, updateInterval);
        }
    };

    public static final int ONGOING_NOTIFICATION_ID = 101;
    public static final String CHANNEL_ID = "1001";

    public static void startService(Context context) {
        Intent intent = new Intent(context, ForegroundService.class);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            context.startService(intent);
        } else {
            context.startForegroundService(intent);
        }
    }

    private void connectUsb(String puerto, int baudRate, String paridad, int bitDato, int bitParada, String comando) {
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        UsbDevice device = null;

        // Obtener el dispositivo USB
        for (UsbDevice v : usbManager.getDeviceList().values()) {
            device = v;
            break;  // Usa el primer dispositivo USB encontrado
        }

        if (device == null) {
            Log.e(TAG, "No USB device found");
            return;
        }

        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if (driver == null || driver.getPorts().isEmpty()) {
            Log.e(TAG, "No USB driver or ports found for the device");
            return;
        }

        usbSerialPort = driver.getPorts().get(0);
        UsbDeviceConnection usbConnection = usbManager.openDevice(driver.getDevice());
        if (usbConnection == null) {
            Log.e(TAG, "Opening USB connection failed");
            return;
        }

        try {
            usbSerialPort.open(usbConnection);
            int parity = paridad.equals("N") ? UsbSerialPort.PARITY_NONE : paridad.equals("E") ? UsbSerialPort.PARITY_EVEN : UsbSerialPort.PARITY_ODD;
            usbSerialPort.setParameters(baudRate, bitDato, bitParada, parity);
        } catch (IOException e) {
            Log.e(TAG, "Error setting up USB connection", e);
            return;
        }

        try {
            JSONArray comandoArray = new JSONArray(comando);
            String comandoFormateado = comandoArray.getString(0);
            Log.d("COMMAND", "FORMATEADO: " + comandoFormateado);
            //startReadingUsbData(comandoFormateado);
            startReadingUsbData("R");
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing JSON command", e);
            // Handle error appropriately, e.g., return or set a default command
            startReadingUsbData("$");
        }
        //startReadingUsbData("$");
    }

    private void startReadingUsbData(String command) {
        new Thread(() -> {
            byte[] buffer = new byte[1028];
            try {
                usbSerialPort.write(command.getBytes(), 1000);
            } catch (IOException e) {
                Log.e(TAG, "Error writing command to USB", e);
            }

            while (true) {
                try {
                    int numBytesRead = usbSerialPort.read(buffer, 1000);
                    if (numBytesRead > 0) {
                        String data = new String(buffer, 0, numBytesRead);
                        //Log.d(TAG, "numBytesRead: " + numBytesRead);
                        //Log.d(TAG, "Received data: " + data);
                        dataBuffer.append(data);
                        processBuffer(command);
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error reading from USB", e);
                    break;
                }
            }
        }).start();
    }

    private void processBuffer(String command) {
        String completeData = dataBuffer.toString();
        matchData = completeData;
        Log.d(TAG, "Complete data: " + completeData);

        Pattern[] patterns = COMMAND_PATTERNS.get(command);

        if (patterns != null) {
            for (Pattern pattern : patterns) {
                Matcher matcher = pattern.matcher(completeData);
                if (matcher.find()) {
                    String matchedData = matcher.group();
                    Log.d(TAG, "Matched data: " + matchedData);

                    receivedData = matchedData
                            .replace("=", "")
                            .replace("c", "")
                            .replace("+", "")
                            .replace("kg", "")
                            .replace("ST,GS,", "")
                            .trim();

                    dataBuffer.delete(0, matcher.end());
                    Log.d(TAG, "Processed weight data: " + receivedData);
                    return;
                }
            }
        }
    }

    public String obtenerDatosDeBascula() {
        if (receivedData.isEmpty()) {
            return "0.0";
        }
        String formattedData = receivedData.replaceFirst("^0+(?!$)", "");
        if (formattedData.startsWith(".")) {
            formattedData = "0" + formattedData;
        }
        return formattedData;
    }

    public String obtenerDatos() {
        return matchData;
    }

    public static void stopService(Context context) {
        Intent intent = new Intent(context, ForegroundService.class);
        context.stopService(intent);
    }

    private static final Set<String> ALLOWED_ORIGINS = new HashSet<>();
    private ServerSocket serverSocket;

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

    private final ExecutorService executorService = Executors.newFixedThreadPool(4);

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
                    Map<String, String> queryParams = getQueryParameters(path);

                    String puerto = queryParams.get("puerto");
                    int baudRate = Integer.parseInt(getOrDefault(queryParams, "baudRate", "9600"));
                    String paridad = getOrDefault(queryParams, "paridad", "N");
                    int bitDato = Integer.parseInt(getOrDefault(queryParams, "bitDeDato", "8"));
                    int bitParada = Integer.parseInt(getOrDefault(queryParams, "bitDeParada", "1"));
                    String comando = getOrDefault(queryParams, "comando","$");

                    Log.d("COMMAND", "Comando: " + comando);
                    connectUsb(puerto, baudRate, paridad, bitDato, bitParada, comando);
                    String data = obtenerDatosDeBascula();
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

        private Map<String, String> getQueryParameters(String path) {
            Map<String, String> queryParams = new HashMap<>();
            String[] queryParts = path.split("\\?");
            if (queryParts.length > 1) {
                String query = queryParts[1];
                String[] params = query.split("&");
                for (String param : params) {
                    String[] keyValue = param.split("=");
                    if (keyValue.length > 1) {
                        queryParams.put(keyValue[0], keyValue[1]);
                    }
                }
            }
            return queryParams;
        }

        private String getOrDefault(Map<String, String> map, String key, String defaultValue) {
            return map.containsKey(key) ? map.get(key) : defaultValue;
        }
    }
}
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
    private UsbSerialPort usbSerialPort;
    private String receivedData = "";
    private StringBuilder dataBuffer = new StringBuilder();
    private NotificationManager notificationManager;
    private boolean isStarted = false;
    private final Handler handler = new Handler();
    private final int updateInterval = 1000; // Intervalo en milisegundos para actualizar la notificación

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
        // Cerrar el ThreadPoolExecutor al destruir la actividad
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
        connectUsb();

        if (!isStarted) {
            makeForeground(obtenerDatosDeBascula());
            isStarted = true;
        }

        // handler.post(updateNotificationTask);
        return START_STICKY;
    }

    private void makeForeground(String data) {
        createServiceNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Aplicación Pesaje Ekored")
                .setContentText("La aplicación se está ejecutando...")
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

    private void updateNotification(String weightData) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Aplicación Pesaje Ekored")
                .setContentText("La Aplicación se esta ejecutando...")
                .setSmallIcon(R.drawable.ic_notification)
                .build();
        notificationManager.notify(ONGOING_NOTIFICATION_ID, notification);
    }

    private final Runnable updateNotificationTask = new Runnable() {
        @Override
        public void run() {
            String weightData = obtenerDatosDeBascula();
            Log.d(TAG, "weightData: " + weightData);
            updateNotification(weightData);
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

    private void connectUsb() {
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
            usbSerialPort.setParameters(9600, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
        } catch (IOException e) {
            Log.e(TAG, "Error setting up USB connection", e);
            return;
        }

        // Start reading data from the USB device
        startReadingUsbData();
    }

    private void startReadingUsbData() {
        new Thread(() -> {
            byte[] buffer = new byte[1028];
            while (true) {
                try {
                    int numBytesRead = usbSerialPort.read(buffer, 1000);

                    if (numBytesRead > 0) {
                        String data = new String(buffer, 0, numBytesRead);
                        Log.d(TAG, "numBytesRead: " + numBytesRead);
                        Log.d(TAG, "Received data: " + data);
                        dataBuffer.append(data);

                        // Process buffer and extract weight data
                        processBuffer();
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error reading from USB", e);
                    break;
                }
            }
        }).start();
    }

    private void processBuffer() {
        // Unificar el buffer en una sola cadena
        String completeData = dataBuffer.toString();
        Log.d(TAG, "Complete data: " + completeData);

        // Regex para capturar el formato XXXX.X
        Pattern pattern1 = Pattern.compile("=c?\\d{4}\\.\\d");
        Matcher matcher1 = pattern1.matcher(completeData);
        if (matcher1.find()) {
            receivedData = matcher1.group().replace("=", "").replace("c", "");
            dataBuffer.delete(0, matcher1.end());
            Log.d(TAG, "Processed weight data (pattern1): " + receivedData);
            return;
        }

        // Regex para capturar el formato + XX.Xkg
        Pattern pattern2 = Pattern.compile("\\+\\s*\\d{1,3}\\.\\dkg");
        Matcher matcher2 = pattern2.matcher(completeData);
        if (matcher2.find()) {
            receivedData = matcher2.group().replace("+", "").replace("kg", "").trim();
            dataBuffer.delete(0, matcher2.end());
            Log.d(TAG, "Processed weight data (pattern2): " + receivedData);
            return;
        }
    }

    public String obtenerDatosDeBascula() {
        if (receivedData.isEmpty()) {
            return "0.0";
        }
        // Eliminar ceros a la izquierda
        String formattedData = receivedData.replaceFirst("^0+(?!$)", "");
        // Asegurar que el formato sea correcto si el valor empieza con un punto
        if (formattedData.startsWith(".")) {
            formattedData = "0" + formattedData;
        }
        return formattedData;
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
}
}
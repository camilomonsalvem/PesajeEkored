package de.kai_morich.simple_usb_terminal;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.os.Build;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.FragmentManager;

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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity implements FragmentManager.OnBackStackChangedListener {

    private static final String TAG = "MainActivity";
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

    private final ExecutorService executorService = Executors.newFixedThreadPool(4);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportFragmentManager().addOnBackStackChangedListener(this);
        if (savedInstanceState == null)
            getSupportFragmentManager().beginTransaction().add(R.id.fragment, new DevicesFragment(), "devices").commit();
        else
            onBackStackChanged();
        new Thread(new HttpServerThread()).start();
        Intent serviceIntent = new Intent(this, BackgroundService.class);
        startService(serviceIntent);
    }

    @Override
    public void onBackStackChanged() {
        getSupportActionBar().setDisplayHomeAsUpEnabled(getSupportFragmentManager().getBackStackEntryCount() > 0);
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        if ("android.hardware.usb.action.USB_DEVICE_ATTACHED".equals(intent.getAction())) {
            TerminalFragment terminal = (TerminalFragment) getSupportFragmentManager().findFragmentByTag("terminal");
            if (terminal != null)
                terminal.status("USB device detected");
        }
        super.onNewIntent(intent);
    }

    public String obtenerNombreDeDispositivo() {
        String deviceName = "";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            deviceName = Settings.Global.getString(getContentResolver(), Settings.Global.DEVICE_NAME);
        }
        if (deviceName == null || deviceName.isEmpty()) {
            deviceName = Build.MODEL;
        }
        return deviceName;
    }

    public String obtenerDatosDeBascula() {
        TerminalFragment terminalFragment = (TerminalFragment) getSupportFragmentManager().findFragmentByTag("terminal");
        if (terminalFragment != null) {
            String receivedData = terminalFragment.getReceivedData();
            try {
                double dataValue = Double.parseDouble(receivedData);
                if (dataValue <= 0) {
                    return "0";
                } else {
                    return receivedData;
                }
            } catch (NumberFormatException e) {
                return "0"; // En caso de que receivedData no sea un número válido
            }
        } else {
            return "No data received";
        }
    }

    // Clase para manejar las solicitudes HTTP en un hilo separado
    private class HttpServerThread implements Runnable {
        @Override
        public void run() {
            ServerSocket serverSocket;
            try {
                // Crear un servidor Socket en localhost en el puerto 5001
                serverSocket = new ServerSocket(5001, 0, InetAddress.getByName("127.0.0.1"));
                while (true) {
                    // Esperar a que llegue una solicitud
                    Socket client = serverSocket.accept();
                    // Manejar la solicitud utilizando el ThreadPoolExecutor
                    executorService.submit(new HttpRequestHandler(client));
                }
            } catch (IOException e) {
                Log.e(TAG, "Error starting server", e);
            }
        }
    }

    // Clase para manejar las solicitudes HTTP
    private class HttpRequestHandler implements Runnable {
        private final Socket clientSocket;

        public HttpRequestHandler(Socket socket) {
            this.clientSocket = socket;
        }

        @Override
        public void run() {
            try {
                // Leer la solicitud del cliente
                StringBuilder requestBuilder = new StringBuilder();
                InputStream inputStream = clientSocket.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    requestBuilder.append(line).append("\r\n");
                }
                String request = requestBuilder.toString();
                Log.d(TAG, "Request: " + request);

                // Obtener el origen de la solicitud
                String origin = null;
                for (String header : request.split("\r\n")) {
                    if (header.startsWith("Origin:")) {
                        origin = header.split(" ")[1];
                        break;
                    }
                }

                // Verificar la ruta de la solicitud
                String[] requestLines = request.split("\n");
                String[] requestLineParts = requestLines[0].split(" ");
                if (requestLineParts.length >= 2) {
                    String method = requestLineParts[0].trim();
                    String path = requestLineParts[1].trim();
                    String httpResponse = "";

                    // Extraer parámetros de la URL si es un GET
                    Map<String, String> queryParams = new HashMap<>();
                    if ("GET".equals(method) && path.contains("?")) {
                        String[] pathParts = path.split("\\?");
                        if (pathParts.length > 1) {
                            path = pathParts[0];
                            String queryString = pathParts[1];
                            String[] queryParamsArray = queryString.split("&");
                            for (String param : queryParamsArray) {
                                String[] keyValue = param.split("=");
                                if (keyValue.length > 1) {
                                    queryParams.put(keyValue[0], URLDecoder.decode(keyValue[1], "UTF-8"));
                                }
                            }
                        }
                    }

                    if (origin != null && ALLOWED_ORIGINS.contains(origin.trim())) {
                        if ("OPTIONS".equals(method)) {
                            // Manejar la solicitud OPTIONS
                            httpResponse = "HTTP/1.1 204 No Content\r\n"
                                    + "Access-Control-Allow-Origin: " + origin + "\r\n"
                                    + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                                    + "Access-Control-Allow-Headers: Content-Type\r\n"
                                    + "\r\n";
                        } else if ("/leer-peso".equals(path) || "/leer-peso/".equals(path)) {
                            // Crear un JSON con los datos de la báscula
                            JSONObject jsonResponse = new JSONObject();
                            try {
                                jsonResponse.put("weight", obtenerDatosDeBascula());
                            } catch (JSONException e) {
                                Log.e(TAG, "JSON error", e);
                            }

                            // Construir la respuesta con encabezados CORS
                            httpResponse = "HTTP/1.1 200 OK\r\n"
                                    + "Content-Type: application/json\r\n"
                                    + "Access-Control-Allow-Origin: " + origin + "\r\n"
                                    + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                                    + "Access-Control-Allow-Headers: Content-Type\r\n"
                                    + "\r\n"
                                    + jsonResponse.toString();
                        } else if ("/device-name".equals(path) || "/device-name/".equals(path)) {
                            // Crear un JSON con el nombre del dispositivo
                            JSONObject jsonResponse = new JSONObject();
                            try {
                                jsonResponse.put("device_name", obtenerNombreDeDispositivo());
                            } catch (JSONException e) {
                                Log.e(TAG, "JSON error", e);
                            }

                            // Construir la respuesta con encabezados CORS
                            httpResponse = "HTTP/1.1 200 OK\r\n"
                                    + "Content-Type: application/json\r\n"
                                    + "Access-Control-Allow-Origin: " + origin + "\r\n"
                                    + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                                    + "Access-Control-Allow-Headers: Content-Type\r\n"
                                    + "\r\n"
                                    + jsonResponse.toString();
                        } else {
                            // Ruta no encontrada, enviar respuesta 404 Not Found con encabezados CORS
                            httpResponse = "HTTP/1.1 404 Not Found\r\n"
                                    + "Access-Control-Allow-Origin: " + origin + "\r\n"
                                    + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                                    + "Access-Control-Allow-Headers: Content-Type\r\n"
                                    + "Content-Length: 0\r\n"
                                    + "\r\n";
                        }
                    } else {
                        // Origen no permitido
                        httpResponse = "HTTP/1.1 403 Forbidden\r\n"
                                + "Content-Type: text/plain\r\n"
                                + "Access-Control-Allow-Origin: *\r\n"
                                + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                                + "Access-Control-Allow-Headers: Content-Type\r\n"
                                + "\r\n"
                                + "Forbidden: CORS policy does not allow access from this origin.";
                    }

                    // Enviar la respuesta al cliente
                    OutputStream outputStream = clientSocket.getOutputStream();
                    outputStream.write(httpResponse.getBytes("UTF-8"));
                    outputStream.flush();
                    clientSocket.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Error handling request", e);
                try {
                    String httpResponse = "HTTP/1.1 500 Internal Server Error\r\n"
                            + "Content-Type: text/plain\r\n"
                            + "Access-Control-Allow-Origin: *\r\n"
                            + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                            + "Access-Control-Allow-Headers: Content-Type\r\n"
                            + "\r\n"
                            + "Error handling request: " + e.getMessage();
                    OutputStream outputStream = clientSocket.getOutputStream();
                    outputStream.write(httpResponse.getBytes("UTF-8"));
                    outputStream.flush();
                    clientSocket.close();
                } catch (IOException ex) {
                    Log.e(TAG, "Error sending error response", ex);
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Cerrar el ThreadPoolExecutor al destruir la actividad
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
        }
    }
}

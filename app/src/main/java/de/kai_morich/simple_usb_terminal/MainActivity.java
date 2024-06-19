package de.kai_morich.simple_usb_terminal;

import android.content.Intent;
import android.os.Bundle;
import android.os.Build;
import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.FragmentManager;
import android.Manifest;
import android.provider.Settings;
import android.util.Log;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements FragmentManager.OnBackStackChangedListener {

    private static final String TAG = "MainActivity";

    private final ExecutorService executorService = Executors.newFixedThreadPool(4);

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    // Permiso concedido, iniciar el servicio
                    ForegroundService.startService(this);
                } else {
                    // Permiso no concedido, manejar la situación según sea necesario
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        FragmentManager fragmentManager = getSupportFragmentManager();
        FragmentManagerSingleton.getInstance().setFragmentManager(fragmentManager);
        getSupportFragmentManager().addOnBackStackChangedListener(this);
        if (savedInstanceState == null)
            getSupportFragmentManager().beginTransaction().add(R.id.fragment, new DevicesFragment(), "devices").commit();
        else
            onBackStackChanged();

        // Request permission for the foreground service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(Manifest.permission.FOREGROUND_SERVICE);
        }


        // Handle button clicks to start and stop the service
        findViewById(R.id.start_button).setOnClickListener(v -> {
            ForegroundService.startService(this);
        });

        findViewById(R.id.stop_button).setOnClickListener(v -> {
            ForegroundService.stopService(this);
        });


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
}
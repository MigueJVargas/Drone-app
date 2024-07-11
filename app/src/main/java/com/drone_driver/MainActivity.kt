@file:OptIn(ExperimentalMaterial3Api::class)

package com.drone_driver

import android.Manifest
import android.content.BroadcastReceiver
import androidx.activity.compose.setContent
import android.annotation.SuppressLint
import android.content.IntentFilter
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.compose.ui.viewinterop.AndroidView
import com.drone_driver.ui.theme.Drone_DriverTheme
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.LatLng
import java.io.IOException
import java.util.*

public val REQUEST_ENABLE_BT = 1

@RequiresApi(Build.VERSION_CODES.S)
public val BLUETOOTH_PERMISSIONS = arrayOf(
    Manifest.permission.BLUETOOTH,
    Manifest.permission.BLUETOOTH_ADMIN,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT
)

class MainActivity : ComponentActivity() {
    @RequiresApi(Build.VERSION_CODES.S)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Drone_DriverTheme {
                BluetoothScreen()
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.S)
@SuppressLint("MissingPermission")
@Composable
fun BluetoothScreen() {
    val context = LocalContext.current
    var dispositivosEncontrados by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    var bluetoothActivado by remember { mutableStateOf(false) }
    var dispositivoSeleccionado by remember { mutableStateOf<BluetoothDevice?>(null) }
    var mensajeConexion by remember { mutableStateOf("") }
    var buscandoDispositivos by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }

    // Manejo de permisos
    val launcherPermisos = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permisos ->
        if (permisos.values.all { it }) {
            // Todos los permisos concedidos, iniciar descubrimiento Bluetooth
            iniciarDescubrimientoBluetooth(context) { activado ->
                bluetoothActivado = activado
                buscandoDispositivos = activado
                dispositivosEncontrados = emptyList() // Limpia la lista de dispositivos
            }
        } else {
            // Al menos un permiso denegado, manejar esto adecuadamente
        }
    }

    // Registra el BroadcastReceiver para descubrir dispositivos
    val bluetoothReceiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        device?.let {
                            dispositivosEncontrados = dispositivosEncontrados + it
                        }
                    }
                }
            }
        }
    }

    // Registra y desregistra el BroadcastReceiver en el ciclo de vida de la composable
    DisposableEffect(Unit) {
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        context.registerReceiver(bluetoothReceiver, filter)

        onDispose {
            context.unregisterReceiver(bluetoothReceiver)
        }
    }

    if (connected) {
        MapScreen()
    } else {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Greeting(name = "Drone Driver User")

                Button(onClick = {
                    if (tienePermisosBluetooth(context)) {
                        iniciarDescubrimientoBluetooth(context) { activado ->
                            bluetoothActivado = activado
                            buscandoDispositivos = activado
                            dispositivosEncontrados = emptyList() // Limpia la lista de dispositivos
                        }
                    } else {
                        launcherPermisos.launch(BLUETOOTH_PERMISSIONS)
                    }
                }) {
                    Text("Buscar Dispositivos Bluetooth")
                }

                // Mostrar mensaje si no hay dispositivos encontrados
                if (bluetoothActivado && buscandoDispositivos && dispositivosEncontrados.isEmpty()) {
                    Text("No se encontraron dispositivos Bluetooth")
                }

                // Muestra los dispositivos encontrados
                if (bluetoothActivado && buscandoDispositivos) {
                    for (device in dispositivosEncontrados) {
                        Button(onClick = { dispositivoSeleccionado = device }) {
                            Text(text = device.name ?: "Dispositivo desconocido")
                        }
                    }
                }

                // Botón para conectar al dispositivo seleccionado
                dispositivoSeleccionado?.let { device ->
                    Button(onClick = {
                        conectarDispositivo(device) { mensaje ->
                            mensajeConexion = mensaje
                            connected = true
                        }
                    }) {
                        Text("Conectar a ${device.name}")
                    }
                }

                // Muestra el mensaje de conexión
                Text(text = mensajeConexion)
            }
        }
    }
}

// Funciones auxiliares

@RequiresApi(Build.VERSION_CODES.S)
private fun tienePermisosBluetooth(context: Context): Boolean {
    return BLUETOOTH_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}

@RequiresApi(Build.VERSION_CODES.S)
@SuppressLint("MissingPermission")
private fun iniciarDescubrimientoBluetooth(context: Context, onBluetoothActivado: (Boolean) -> Unit) {
    val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    if (bluetoothAdapter == null) {
        // El dispositivo no soporta Bluetooth. Maneja este caso.
        onBluetoothActivado(false)
    } else {
        if (!bluetoothAdapter.isEnabled) {
            // Solicita al usuario que active Bluetooth.
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            if (context is Activity) {
                context.startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            }
            onBluetoothActivado(false)
        } else {
            // Inicia el descubrimiento de dispositivos.
            val hasPermission = tienePermisosBluetooth(context)
            if (hasPermission) {
                bluetoothAdapter.startDiscovery()
                onBluetoothActivado(true)
            }
        }
    }
}

@SuppressLint("MissingPermission")
private fun conectarDispositivo(device: BluetoothDevice, onConexionCompletada: (String) -> Unit) {
    try {
        val uuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        val socket: BluetoothSocket? = device.createRfcommSocketToServiceRecord(uuid)
        socket?.connect()
        onConexionCompletada("Conectado a ${device.name}")
    } catch (e: IOException) {
        onConexionCompletada("Error al conectar: ${e.message}")
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@SuppressLint("JavascriptInterface")
@Composable
fun MapScreen() {
    val context = LocalContext.current
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    var currentLocation by remember { mutableStateOf<LatLng?>(null) }

    // Definir launcherPermisos para la solicitud de permisos
    val launcherPermisos = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Permiso concedido, realizar la acción que requiere permiso
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                location?.let {
                    currentLocation = LatLng(it.latitude, it.longitude)
                }
            }
        } else {
            // Permiso no concedido, manejar esto adecuadamente (puede mostrar un mensaje al usuario)
        }
    }
    LaunchedEffect(Unit) {
        // Verificar y solicitar permiso de ubicación si es necesario
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            // Permiso concedido, obtener la última ubicación conocida
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                location?.let {
                    currentLocation = LatLng(it.latitude, it.longitude)
                }
            }
        } else {
            // Permiso no concedido, solicitar permisos al usuario
            launcherPermisos.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION).toString())
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            currentLocation?.let { location ->
                AndroidView(factory = { context ->
                    val webView = android.webkit.WebView(context)
                    webView.settings.javaScriptEnabled = true
                    webView.loadUrl("file:///android_asset/leaflet_map.html") // Carga el archivo HTML
                    webView.addJavascriptInterface(
                        AndroidToJsInterface(location.latitude, location.longitude),
                        "Android"
                    )
                    webView
                })
            } ?: run {
                Text("Ubicación no disponible")
            }
        }
    }
}

// Clase para la interfaz entre Android y JavaScript
class AndroidToJsInterface(private val latitude: Double, private val longitude: Double) {

    // Método para obtener la ubicación actual en JavaScript
    @JavascriptInterface
    fun getLocation(): String {
        return "$latitude,$longitude"
    }
}

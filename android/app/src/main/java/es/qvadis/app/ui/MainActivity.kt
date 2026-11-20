package es.qvadis.app.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.materialswitch.MaterialSwitch
import es.qvadis.app.R
import es.qvadis.app.model.Account
import es.qvadis.app.model.CallEntry
import es.qvadis.app.net.ApiClient
import es.qvadis.app.push.Notifications
import es.qvadis.app.service.SipService
import es.qvadis.app.store.AccountStore
import es.qvadis.app.store.Prefs
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Pantalla principal con menú inferior (como el panel): Inicio (cámara + abrir),
 * Actividad (eventos recientes) y Ajustes (submenú: emparejar, tono, desemparejar).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var acc: Account
    private lateinit var api: ApiClient
    private lateinit var camera: MjpegView
    private lateinit var cameraPlaceholder: TextView
    private val adapter = CallsAdapter()
    private var dndSched = false
    private var dndFrom = "23:00"
    private var dndTo = "08:00"

    private lateinit var pageHome: View
    private lateinit var pageActivity: View
    private lateinit var pageSettings: View
    private lateinit var activityEmpty: TextView

    private val pickRingtone = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val uri: Uri? = res.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            Prefs.setRingtoneUri(this, uri?.toString())
            Notifications.applyRingtone(this)
            refreshRingtoneLabel()
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)

        val a = AccountStore.load(this)
        if (a == null) {                              // sin emparejar -> escanear QR
            startActivity(Intent(this, PairActivity::class.java)); finish(); return
        }
        acc = a; api = ApiClient(acc)
        setContentView(R.layout.activity_main)

        pageHome = findViewById(R.id.page_home)
        pageActivity = findViewById(R.id.page_activity)
        pageSettings = findViewById(R.id.page_settings)
        activityEmpty = findViewById(R.id.activity_empty)
        camera = findViewById(R.id.camera_view)
        cameraPlaceholder = findViewById(R.id.camera_placeholder)

        Notifications.ensureChannels(this)
        requestRuntimePermissions()

        // Inicio
        findViewById<SlideToOpen>(R.id.slide_open).onOpen = { openDoor() }

        // Actividad
        findViewById<RecyclerView>(R.id.calls_list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        // Ajustes (submenú)
        findViewById<TextView>(R.id.acc_info).text = "Portero: ${acc.sipDomain()}\nExtensión: ${acc.ext}"
        findViewById<View>(R.id.row_pair).setOnClickListener {
            startActivity(Intent(this, PairActivity::class.java))
        }
        findViewById<View>(R.id.row_tone).setOnClickListener { pickSystemRingtone() }
        findViewById<View>(R.id.row_unpair).setOnClickListener { confirmUnpair() }
        setupPortero()
        refreshRingtoneLabel()

        // Menú inferior
        val nav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        nav.setOnItemSelectedListener { showTab(it.itemId); true }
        showTab(R.id.tab_home)

        // Servicio en primer plano: mantiene el SIP registrado siempre (suena al instante).
        SipService.start(this)
        requestBatteryExemption()
        requestFullScreenIntentPermission()   // llamada a pantalla completa (no notificación pequeña)

        // Registrar el token FCM en la placa (respaldo: si el sistema matara el servicio,
        // el push puede despertarlo). Esperamos al token para no perder el callback asíncrono.
        thread {
            runCatching {
                val token = com.google.android.gms.tasks.Tasks.await(
                    com.google.firebase.messaging.FirebaseMessaging.getInstance().token)
                api.registerFcm(token)
            }
        }
    }

    private fun showTab(id: Int) {
        pageHome.visibility = if (id == R.id.tab_home) View.VISIBLE else View.GONE
        pageActivity.visibility = if (id == R.id.tab_activity) View.VISIBLE else View.GONE
        pageSettings.visibility = if (id == R.id.tab_settings) View.VISIBLE else View.GONE
        if (id == R.id.tab_home) startCamera() else camera.stop()
    }

    override fun onResume() {
        super.onResume()
        refreshCameraAndCalls()
    }

    override fun onPause() {
        super.onPause()
        camera.stop()
    }

    private fun startCamera() {
        thread {
            val hasCam = runCatching { api.hasCamera() }.getOrDefault(false)
            runOnUiThread {
                if (hasCam) {
                    cameraPlaceholder.visibility = View.GONE
                    camera.visibility = View.VISIBLE
                    camera.start(api.cameraStreamUrl(), api.http)
                } else {
                    camera.stop()
                    camera.visibility = View.GONE
                    cameraPlaceholder.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun refreshCameraAndCalls() {
        if (pageHome.visibility == View.VISIBLE) startCamera()
        thread {
            val calls = runCatching { api.calls() }.getOrDefault(emptyList())
            runOnUiThread {
                adapter.submit(calls)
                activityEmpty.visibility = if (calls.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun openDoor() {
        thread {
            val ok = runCatching { api.openDoor("front") }.getOrDefault(false)
            runOnUiThread {
                Toast.makeText(this, if (ok) R.string.door_opened else R.string.door_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---- Ajustes ----
    private fun pickSystemRingtone() {
        val current = runCatching { Uri.parse(Prefs.ringtoneUri(this)) }.getOrNull()
        val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Tono de la puerta")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
        }
        pickRingtone.launch(i)
    }

    private fun refreshRingtoneLabel() {
        val uri = runCatching { Uri.parse(Prefs.ringtoneUri(this)) }.getOrNull()
        val name = uri?.let { RingtoneManager.getRingtone(this, it)?.getTitle(this) } ?: "Por defecto"
        findViewById<TextView>(R.id.tone_label).text = name
    }

    /** Ajustes del portero manejables desde la app: No molestar + volúmenes (vía HTTPS con token). */
    private fun setupPortero() {
        val dnd = findViewById<MaterialSwitch>(R.id.set_dnd)
        val ringv = findViewById<SeekBar>(R.id.set_ringvol)
        val spk = findViewById<SeekBar>(R.id.set_spkstreet)
        val mic = findViewById<SeekBar>(R.id.set_micstreet)
        thread {
            val d = runCatching { api.dnd() }.getOrNull()
            val a = runCatching { api.audio() }.getOrNull()
            val r = runCatching { api.ringtone() }.getOrNull()
            runOnUiThread {
                d?.let {
                    dndSched = it.optBoolean("sched", false)
                    dndFrom = it.optString("from", "23:00"); dndTo = it.optString("to", "08:00")
                    dnd.isChecked = it.optBoolean("enabled", false)
                }
                r?.let { ringv.progress = it.optInt("volume", 85) }
                a?.let { spk.progress = it.optInt("spkStreet", 70); mic.progress = it.optInt("micStreet", 90) }
                // listeners después de fijar los valores iniciales (para no reenviar al cargar)
                dnd.setOnCheckedChangeListener { _, on -> thread { runCatching { api.setDnd(on, dndSched, dndFrom, dndTo) } } }
                ringv.setOnSeekBarChangeListener(volListener { api.setRingVolume(it) })
                spk.setOnSeekBarChangeListener(volListener { api.setAudio("spkStreet", it) })
                mic.setOnSeekBarChangeListener(volListener { api.setAudio("micStreet", it) })
            }
        }
    }

    private fun volListener(apply: (Int) -> Boolean) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) {}
        override fun onStartTrackingTouch(sb: SeekBar) {}
        override fun onStopTrackingTouch(sb: SeekBar) { val v = sb.progress; thread { runCatching { apply(v) } } }
    }

    private fun confirmUnpair() {
        AlertDialog.Builder(this)
            .setTitle("Desemparejar")
            .setMessage("Se borrarán las credenciales de este móvil. Tendrás que volver a escanear el QR.")
            .setPositiveButton("Desemparejar") { _, _ ->
                AccountStore.clear(this)
                Toast.makeText(this, "Desemparejado", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, PairActivity::class.java))
                finishAffinity()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun requestRuntimePermissions() {
        val need = mutableListOf<String>()
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            need.add(android.Manifest.permission.POST_NOTIFICATIONS)
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            need.add(android.Manifest.permission.RECORD_AUDIO)
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 42)
    }

    /** En Android 14+ el full-screen intent está restringido: sin este permiso, la llamada sale
     *  como notificación pequeña en vez de a pantalla completa. Lo pedimos (una vez concedido, no molesta). */
    private fun requestFullScreenIntentPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            if (nm != null && !nm.canUseFullScreenIntent()) {
                runCatching {
                    startActivity(Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        android.net.Uri.parse("package:$packageName")))
                }
            }
        }
    }

    /** Pide excluir la app de la optimización de batería (para que el servicio no muera). */
    private fun requestBatteryExemption() {
        val pm = getSystemService(android.os.PowerManager::class.java) ?: return
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            runCatching {
                startActivity(Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:$packageName")))
            }
        }
    }

    /** Adapter simple con carga de miniaturas por el cliente con pinning. */
    inner class CallsAdapter : RecyclerView.Adapter<CallsAdapter.VH>() {
        private val items = ArrayList<CallEntry>()
        private val fmt = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())

        @SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<CallEntry>) { items.clear(); items.addAll(list); notifyDataSetChanged() }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val photo: ImageView = v.findViewById(R.id.call_photo)
            val whenTv: TextView = v.findViewById(R.id.call_when)
            val stateTv: TextView = v.findViewById(R.id.call_state)
        }

        override fun onCreateViewHolder(parent: ViewGroup, vt: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_call, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val c = items[pos]
            h.whenTv.text = if (c.ts > 0) fmt.format(Date(c.ts)) else "—"
            h.stateTv.text = c.label()
            h.photo.setImageDrawable(null)
            if (c.photoUrl.isNotBlank()) loadThumb(c.photoUrl, h.photo)
        }

        private fun loadThumb(url: String, iv: ImageView) {
            val tag = url; iv.tag = tag
            thread {
                val bmp = runCatching {
                    api.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                        r.body?.byteStream()?.let { BitmapFactory.decodeStream(it) }
                    }
                }.getOrNull()
                if (bmp != null) runOnUiThread { if (iv.tag == tag) iv.setImageBitmap(bmp) }
            }
        }
    }
}

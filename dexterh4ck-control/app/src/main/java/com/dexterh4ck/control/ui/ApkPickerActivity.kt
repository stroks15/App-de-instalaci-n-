package com.dexterh4ck.control.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Actividad para seleccionar un APK del almacenamiento del teléfono.
 * Al seleccionar un archivo, lo devuelve a la actividad anterior.
 */
class ApkPickerActivity : AppCompatActivity() {

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val intent = Intent()
            intent.putExtra("apk_uri", uri.toString())
            setResult(Activity.RESULT_OK, intent)
            finish()
        } else {
            Toast.makeText(this, "No se seleccionó ningún archivo", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(com.dexterh4ck.control.R.layout.activity_apk_picker)

        val btnBack = findViewById<ImageButton>(com.dexterh4ck.control.R.id.btnBack)
        btnBack.setOnClickListener { finish() }

        // Abrir selector de archivos al inicio
        filePickerLauncher.launch("*/*")
    }
}
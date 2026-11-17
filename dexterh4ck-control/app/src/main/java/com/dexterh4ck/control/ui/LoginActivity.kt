package com.dexterh4ck.control.ui
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.dexterh4ck.control.data.AuthStore
import com.dexterh4ck.control.data.ApiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class LoginActivity:AppCompatActivity(){
 private lateinit var email:EditText
 private lateinit var password:EditText
 private lateinit var submit:Button
 override fun onCreate(savedInstanceState:Bundle?){
  super.onCreate(savedInstanceState)
  if(!AuthStore.accessToken(this).isNullOrBlank()){openDashboard();return}
  val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(32,32,32,32)}
  root.addView(TextView(this).apply{text="DEXTERH4CK CONTROL\nInicia sesión para administrar tus equipos";textSize=22f;gravity=Gravity.CENTER})
  email=EditText(this).apply{hint="Correo electrónico";inputType=33}
  password=EditText(this).apply{hint="Contraseña";inputType=129}
  submit=Button(this).apply{text="Iniciar sesión"}
  root.addView(email,LinearLayout.LayoutParams(-1,-2));root.addView(password,LinearLayout.LayoutParams(-1,-2));root.addView(submit,LinearLayout.LayoutParams(-1,-2))
  setContentView(root);submit.setOnClickListener{signIn()}
 }
 private fun signIn(){
  val user=email.text.toString().trim();val pass=password.text.toString()
  if(user.isBlank()||pass.isBlank()){Toast.makeText(this,"Ingresa correo y contraseña",Toast.LENGTH_SHORT).show();return}
  submit.isEnabled=false
  lifecycleScope.launch{
   try{
    val result=withContext(Dispatchers.IO){
     val body=JSONObject().put("email",user).put("password",pass).toString().toRequestBody("application/json".toMediaType())
     val req=Request.Builder().url("${ApiConfig.SUPABASE_URL.trimEnd('/')}/auth/v1/token?grant_type=password").header("apikey","sb_publishable_3GDyrM0JTII5TPZ6sAOJnA_YDtxjbiw").post(body).build()
     OkHttpClient().newCall(req).execute().use{response->
      val payload=JSONObject(response.body?.string()?: "{}")
      if(!response.isSuccessful)throw IllegalStateException(payload.optString("msg",payload.optString("message","Credenciales incorrectas")))
      payload
     }
    }
    val access=result.optString("access_token");val refresh=result.optString("refresh_token")
    if(access.isBlank()||refresh.isBlank())throw IllegalStateException("No se recibió una sesión válida")
    AuthStore.save(this@LoginActivity,access,refresh);openDashboard()
   }catch(e:Exception){Toast.makeText(this@LoginActivity,e.message?:"No se pudo iniciar sesión",Toast.LENGTH_LONG).show();submit.isEnabled=true}
  }
 }
 private fun openDashboard(){startActivity(Intent(this,DeviceListActivity::class.java));finish()}
}

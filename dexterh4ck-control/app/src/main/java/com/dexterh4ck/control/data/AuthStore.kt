package com.dexterh4ck.control.data
import android.content.Context
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object AuthStore {
 private const val PREFS="dexter_control_auth"
 private const val ACCESS="access_token"
 private const val REFRESH="refresh_token"
 private const val SUPABASE_URL="https://jnrnbbkjcvxeplyljkyj.supabase.co"
 private const val API_KEY="sb_publishable_3GDyrM0JTII5TPZ6sAOJnA_YDtxjbiw"
 fun accessToken(c:Context)=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(ACCESS,null)
 fun save(c:Context,a:String,r:String){c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(ACCESS,a).putString(REFRESH,r).apply()}
 fun clear(c:Context){c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().clear().apply()}
 fun client(context:Context):OkHttpClient {
  val c=context.applicationContext
  return OkHttpClient.Builder().addInterceptor { chain ->
   val token=accessToken(c)
   val req=chain.request().newBuilder().apply{if(!token.isNullOrBlank())header("Authorization","Bearer $token")}.build()
   chain.proceed(req)
  }.authenticator(object:Authenticator{
   override fun authenticate(route:Route?,response:Response):Request? {
    if(responseCount(response)>=2)return null
    val refresh=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(REFRESH,null)?:return null
    return try {
     val body=JSONObject().put("refresh_token",refresh).toString().toRequestBody("application/json".toMediaType())
     val req=Request.Builder().url("$SUPABASE_URL/auth/v1/token?grant_type=refresh_token").header("apikey",API_KEY).post(body).build()
     OkHttpClient().newCall(req).execute().use { result ->
      if(!result.isSuccessful){clear(c);return null}
      val json=JSONObject(result.body?.string()?:return null)
      val access=json.optString("access_token")
      if(access.isBlank())return null
      save(c,access,json.optString("refresh_token",refresh))
      response.request.newBuilder().header("Authorization","Bearer $access").build()
     }
    }catch(_:Exception){null}
   }
  }).build()
 }
 private fun responseCount(r:Response):Int{var n=1;var p=r.priorResponse;while(p!=null){n++;p=p.priorResponse};return n}
}

import {NextRequest,NextResponse} from 'next/server';
import {getSupabaseAdmin} from '@/lib/supabase';
import {requireUser} from '@/lib/request-auth';
export const dynamic='force-dynamic';
export async function POST(request:NextRequest){
 const a=await requireUser(request);if(a.response||!a.user)return a.response;
 try{
  const b=await request.json(),code=String(b.pairing_code||'').trim().toUpperCase();
  if(!/^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{5}$/.test(code))return NextResponse.json({error:'Código inválido'},{status:400});
  const db=getSupabaseAdmin(),{data,error}=await db.from('devices').update({owner_id:a.user.id,paired_at:new Date().toISOString(),updated_at:new Date().toISOString()}).eq('pairing_code',code).is('owner_id',null).select('id,device_id,name,status,last_seen,kiosk_enabled').maybeSingle();
  if(error)return NextResponse.json({error:'No se pudo vincular el equipo'},{status:500});
  if(!data)return NextResponse.json({error:'Código incorrecto, expirado o ya vinculado'},{status:404});
  await db.from('device_audit_logs').insert({device_id:data.id,owner_id:a.user.id,actor_id:a.user.id,action:'device_paired',metadata:{}});
  return NextResponse.json({device:data});
 }catch{return NextResponse.json({error:'Solicitud inválida'},{status:400});}
}

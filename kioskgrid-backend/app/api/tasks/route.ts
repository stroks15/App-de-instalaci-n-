import { NextRequest, NextResponse } from 'next/server';
import { getSupabaseAdmin } from '@/lib/supabase';
import { requireUser } from '@/lib/request-auth';
export const dynamic = 'force-dynamic';

export async function GET(request: NextRequest) {
 const auth=await requireUser(request); if(auth.response||!auth.user)return auth.response;
 const p=new URL(request.url).searchParams, deviceRef=p.get('deviceId'), status=p.get('status'), type=p.get('type');
 const limit=Math.min(Math.max(Number(p.get('limit')||50)||50,1),100), db=getSupabaseAdmin();
 let q=db.from('tasks').select('*').order('created_at',{ascending:false}).limit(limit);
 const {data:owned}=await db.from('devices').select('id,device_id').eq('owner_id',auth.user.id);
 const devices=owned??[];
 if(deviceRef){const d=devices.find(x=>x.id===deviceRef||x.device_id===deviceRef);if(!d)return NextResponse.json({error:'Equipo no encontrado'},{status:404});q=q.eq('device_id',d.id);}
 else q=q.in('device_id',devices.map(x=>x.id));
 if(status)q=q.eq('status',status); if(type)q=q.eq('type',type);
 const {data,error}=await q; if(error)return NextResponse.json({error:'No se pudieron consultar las tareas'},{status:500});
 return NextResponse.json(data??[]);
}
export async function POST(request:NextRequest){
 const auth=await requireUser(request);if(auth.response||!auth.user)return auth.response;
 try{
  const b=await request.json(), ref=String(b.device_id||''), type=String(b.type||'');
  const allowed=['install','uninstall','launch','list_apps','device_info','reboot','pin_app','notify','kiosk_enable','kiosk_disable','block_app','unblock_app','get_usage'];
  if(!ref||!allowed.includes(type))return NextResponse.json({error:'Equipo o tipo de tarea no válido'},{status:400});
  const db=getSupabaseAdmin(),{data:owned}=await db.from('devices').select('id,device_id').eq('owner_id',auth.user.id);
  const d=(owned??[]).find(x=>x.id===ref||x.device_id===ref);if(!d)return NextResponse.json({error:'Equipo no encontrado'},{status:404});
  const {data,error}=await db.from('tasks').insert({device_id:d.id,type,payload:b.payload??null,status:'queued',created_at:new Date().toISOString(),updated_at:new Date().toISOString()}).select('*').single();
  if(error)return NextResponse.json({error:'No se pudo crear la tarea'},{status:500});
  await db.from('device_audit_logs').insert({device_id:d.id,owner_id:auth.user.id,actor_id:auth.user.id,action:'task_created',metadata:{task_id:data.id,type}});
  return NextResponse.json(data,{status:201});
 }catch{return NextResponse.json({error:'Solicitud inválida'},{status:400});}
}
export async function PATCH(){return NextResponse.json({error:'Los estados de ejecución sólo los reporta el agente autenticado'},{status:405});}

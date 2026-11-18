import {NextRequest,NextResponse} from 'next/server';
import {getSupabaseAdmin} from '@/lib/supabase';
import {requireUser} from '@/lib/request-auth';
export const dynamic='force-dynamic';
export async function GET(request:NextRequest){
 const auth=await requireUser(request);if(auth.response||!auth.user)return auth.response;
 const deviceRef=new URL(request.url).searchParams.get('deviceId');
 if(!deviceRef)return NextResponse.json({error:'deviceId es requerido'},{status:400});
 const db=getSupabaseAdmin(),{data:owned}=await (db.from('devices') as any).select('id,device_id').eq('owner_id',auth.user.id);
 const device=(owned??[]).find(d=>d.id===deviceRef||d.device_id===deviceRef);
 if(!device)return NextResponse.json({error:'Equipo no encontrado'},{status:404});
 const {data,error}=await db.from('tasks').select('id,result,updated_at').eq('device_id',device.id).eq('type','list_apps').eq('status','done').order('created_at',{ascending:false}).limit(1);
 if(error)return NextResponse.json({error:'No se pudieron consultar las aplicaciones'},{status:500});
 const task=data?.[0];let apps:any[]=[];if(task?.result){try{apps=JSON.parse(task.result)}catch{apps=[]}}
 return NextResponse.json({deviceId:device.device_id,apps,updated_at:task?.updated_at??null,task_id:task?.id??null});
}

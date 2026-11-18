import {NextRequest,NextResponse} from 'next/server';
import {getSupabaseAdmin} from '@/lib/supabase';
import {requireUser} from '@/lib/request-auth';
export const dynamic='force-dynamic';
type C={params:{taskId:string}};
export async function GET(request:NextRequest,{params}:C){
 const a=await requireUser(request);if(a.response||!a.user)return a.response;
 const db=getSupabaseAdmin(),{data:owned}=await (db.from('devices') as any).select('id').eq('owner_id',a.user.id);
 const {data,error}=await (db.from('tasks') as any).select('*').eq('id',params.taskId).in('device_id',(owned??[]).map((x: any)=>x.id)).maybeSingle();
 if(error)return NextResponse.json({error:'No se pudo consultar la tarea'},{status:500});
 if(!data)return NextResponse.json({error:'Tarea no encontrada'},{status:404});
 return NextResponse.json({task:data});
}
export async function PATCH(){return NextResponse.json({error:'Los estados de ejecución sólo los reporta el agente autenticado'},{status:405});}
export async function DELETE(request:NextRequest,{params}:C){
 const a=await requireUser(request);if(a.response||!a.user)return a.response;
 const db=getSupabaseAdmin(),{data:owned}=await (db.from('devices') as any).select('id').eq('owner_id',a.user.id);
 const {data,error}=await ((db.from('tasks') as any) as any).delete().eq('id',params.taskId).in('device_id',(owned??[]).map((x: any)=>x.id)).select('id').maybeSingle();
 if(error)return NextResponse.json({error:'No se pudo eliminar la tarea'},{status:500});
 if(!data)return NextResponse.json({error:'Tarea no encontrada'},{status:404});
 return NextResponse.json({message:'Tarea eliminada'});
}

import {NextRequest,NextResponse} from 'next/server';
import {getSupabaseAdmin} from '@/lib/supabase';
import {requireUser} from '@/lib/request-auth';
export const dynamic='force-dynamic';
type C={params:{deviceId:string}};
async function findOwned(db:any,ref:string,owner:string){
 const {data:all}=await (db.from('devices') as any).select('*').eq('owner_id',owner);
 return (all??[]).find((d:any)=>d.id===ref||d.device_id===ref)||null;
}
export async function GET(request:NextRequest,{params}:C){
 const a=await requireUser(request);if(a.response||!a.user)return a.response;
 const db=getSupabaseAdmin(),d=await findOwned(db,params.deviceId,a.user.id);
 if(!d)return NextResponse.json({error:'Equipo no encontrado'},{status:404});
 const {data:tasks}=await (db.from('tasks') as any).select('*').eq('device_id',d.id).order('created_at',{ascending:false}).limit(20);
 const appTask=(tasks??[]).find((t:any)=>t.type==='list_apps'&&t.status==='done');
 let apps:any[]=[];if(appTask?.result){try{apps=JSON.parse(appTask.result)}catch{apps=[]}}
 return NextResponse.json({device:d,tasks:tasks??[],installed_apps:apps,apps_updated_at:appTask?.updated_at??null});
}
export async function PUT(request:NextRequest,{params}:C){
 const a=await requireUser(request);if(a.response||!a.user)return a.response;
 const b=await request.json(),name=typeof b.name==='string'?b.name.trim().slice(0,80):'';
 if(!name)return NextResponse.json({error:'Nombre inválido'},{status:400});
 const db=getSupabaseAdmin(),d=await findOwned(db,params.deviceId,a.user.id);
 if(!d)return NextResponse.json({error:'Equipo no encontrado'},{status:404});
 const {data,error}=await (db.from('devices') as any).update({name,updated_at:new Date().toISOString()}).eq('id',d.id).select('*').single();
 if(error)return NextResponse.json({error:'No se pudo actualizar el equipo'},{status:500});
 return NextResponse.json({device:data});
}
export async function DELETE(request:NextRequest,{params}:C){
 const a=await requireUser(request);if(a.response||!a.user)return a.response;
 const db=getSupabaseAdmin(),d=await findOwned(db,params.deviceId,a.user.id);
 if(!d)return NextResponse.json({error:'Equipo no encontrado'},{status:404});
 const {error}=await (db.from('devices') as any).delete().eq('id',d.id);
 if(error)return NextResponse.json({error:'No se pudo eliminar el equipo'},{status:500});
 return NextResponse.json({message:'Equipo eliminado correctamente'});
}

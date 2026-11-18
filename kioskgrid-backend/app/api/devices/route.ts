import {NextRequest,NextResponse} from 'next/server';
import {getSupabaseAdmin} from '@/lib/supabase';
import {requireUser} from '@/lib/request-auth';
export const dynamic='force-dynamic';
const ONLINE_WINDOW_MS = 90_000;
function withFreshStatus(device: any) {
 const lastSeen = device.last_seen ? Date.parse(device.last_seen) : NaN;
 const online = Number.isFinite(lastSeen) && Date.now() - lastSeen <= ONLINE_WINDOW_MS;
 return {...device,status:online ? device.status : 'offline'};
}
export async function GET(request:NextRequest){
 const a=await requireUser(request);if(a.response||!a.user)return a.response;
 const requestedStatus=new URL(request.url).searchParams.get('status'),db=getSupabaseAdmin();
 const {data,error}=await (db.from('devices') as any).select('*').eq('owner_id',a.user.id).order('last_seen',{ascending:false});
 if(error)return NextResponse.json({error:'No se pudieron consultar los equipos'},{status:500});
 const devices=(data??[]).map(withFreshStatus);
 return NextResponse.json(requestedStatus ? devices.filter((d:any)=>d.status===requestedStatus) : devices);
}
export async function POST(){
 return NextResponse.json({error:'Usa /api/agent/register. El registro de agentes requiere credencial individual.'},{status:410});
}

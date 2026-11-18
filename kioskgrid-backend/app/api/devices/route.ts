import {NextRequest,NextResponse} from 'next/server';
import {getSupabaseAdmin} from '@/lib/supabase';
import {requireUser} from '@/lib/request-auth';
export const dynamic='force-dynamic';
export async function GET(request:NextRequest){
 const a=await requireUser(request);if(a.response||!a.user)return a.response;
 const status=new URL(request.url).searchParams.get('status'),db=getSupabaseAdmin();
 let q=(db.from('devices') as any).select('*').eq('owner_id',a.user.id);
 if(status)q=q.eq('status',status);
 const {data,error}=await q.order('last_seen',{ascending:false});
 if(error)return NextResponse.json({error:'No se pudieron consultar los equipos'},{status:500});
 return NextResponse.json(data??[]);
}
export async function POST(){
 return NextResponse.json({error:'Usa /api/agent/register. El registro de agentes requiere credencial individual.'},{status:410});
}

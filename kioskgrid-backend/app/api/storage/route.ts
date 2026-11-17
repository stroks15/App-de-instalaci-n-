import {NextRequest,NextResponse} from 'next/server';
import {getSupabaseAdmin} from '@/lib/supabase';
import {requireUser} from '@/lib/request-auth';
export const dynamic='force-dynamic';
const BUCKET='apks';
const MAX_FILE_SIZE=512*1024*1024;
function safeSegment(value:string){return value.replace(/[^a-zA-Z0-9._-]/g,'_').replace(/^\.+/,'').slice(0,120)}
export async function GET(request:NextRequest){
 const auth=await requireUser(request);if(auth.response||!auth.user)return auth.response;
 const p=new URL(request.url).searchParams,requested=p.get('path')||auth.user.id;
 if(p.get('bucket')&&p.get('bucket')!==BUCKET)return NextResponse.json({error:'Bucket no permitido'},{status:400});
 if(requested!==auth.user.id&&!requested.startsWith(auth.user.id+'/'))return NextResponse.json({error:'Ruta no autorizada'},{status:403});
 const limit=Math.min(Math.max(Number(p.get('limit')||100)||100,1),200),offset=Math.max(Number(p.get('offset')||0)||0,0);
 const db=getSupabaseAdmin(),{data,error}=await db.storage.from(BUCKET).list(requested,{limit,offset,sortBy:{column:'created_at',order:'desc'}});
 if(error)return NextResponse.json({error:'No se pudieron listar los archivos'},{status:500});
 const files=await Promise.all((data??[]).map(async f=>{
  const path=`${requested}/${f.name}`,{data:signed}=await db.storage.from(BUCKET).createSignedUrl(path,86400);
  return {...f,path,url:signed?.signedUrl??null};
 }));
 return NextResponse.json({bucket:BUCKET,files});
}
export async function POST(request:NextRequest){
 const auth=await requireUser(request);if(auth.response||!auth.user)return auth.response;
 try{
  const form=await request.formData(),file=form.get('file');
  if(!(file instanceof File))return NextResponse.json({error:'No se proporcionó archivo'},{status:400});
  if(file.size<=0||file.size>MAX_FILE_SIZE)return NextResponse.json({error:'El archivo supera el tamaño permitido'},{status:413});
  if(!['application/vnd.android.package-archive','application/octet-stream'].includes(file.type))return NextResponse.json({error:'Sólo se permiten archivos APK'},{status:415});
  if(form.get('bucket')&&form.get('bucket')!==BUCKET)return NextResponse.json({error:'Bucket no permitido'},{status:400});
  const name=safeSegment(file.name);if(!name.toLowerCase().endsWith('.apk'))return NextResponse.json({error:'El archivo debe tener extensión .apk'},{status:415});
  const folder=safeSegment(String(form.get('path')||'')).replace(/^\/+|\/+$/g,'');
  const fullPath=[auth.user.id,folder,name?`${Date.now()}-${name}`:'' ].filter(Boolean).join('/');
  const db=getSupabaseAdmin();
  const {error}=await db.storage.from(BUCKET).upload(fullPath,Buffer.from(await file.arrayBuffer()),{
   cacheControl:'3600',upsert:false,contentType:'application/vnd.android.package-archive'
  });
  if(error)return NextResponse.json({error:'No se pudo guardar el APK'},{status:500});
  const {data:signed,error:signedError}=await db.storage.from(BUCKET).createSignedUrl(fullPath,86400);
  if(signedError||!signed?.signedUrl)return NextResponse.json({error:'No se pudo generar el enlace temporal'},{status:500});
  return NextResponse.json({path:fullPath,name:file.name,size:file.size,url:signed.signedUrl,bucket:BUCKET},{status:201});
 }catch{return NextResponse.json({error:'Solicitud de archivo inválida'},{status:400});}
}
export async function DELETE(request:NextRequest){
 const auth=await requireUser(request);if(auth.response||!auth.user)return auth.response;
 const p=new URL(request.url).searchParams,path=p.get('path')||'';
 if(p.get('bucket')&&p.get('bucket')!==BUCKET)return NextResponse.json({error:'Bucket no permitido'},{status:400});
 if(!path||!path.startsWith(auth.user.id+'/'))return NextResponse.json({error:'Ruta no autorizada'},{status:403});
 const {error}=await getSupabaseAdmin().storage.from(BUCKET).remove([path]);
 if(error)return NextResponse.json({error:'No se pudo eliminar el archivo'},{status:500});
 return NextResponse.json({message:'Archivo eliminado correctamente'});
}

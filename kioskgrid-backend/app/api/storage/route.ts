import { NextRequest, NextResponse } from 'next/server';
import { getSupabaseAdmin } from '@/lib/supabase';

export const dynamic = 'force-dynamic';

/**
 * GET /api/storage?bucket=apks&path=&limit=100
 * Lista archivos en un bucket de Supabase Storage.
 */
export async function GET(request: NextRequest) {
  try {
    const { searchParams } = new URL(request.url);
    const bucket = searchParams.get('bucket') || 'apks';
    const path = searchParams.get('path') || '';
    const limit = parseInt(searchParams.get('limit') || '100');
    const offset = parseInt(searchParams.get('offset') || '0');

    const supabase = getSupabaseAdmin();
    const { data, error } = await supabase.storage
      .from(bucket)
      .list(path, { limit, offset, sortBy: { column: 'created_at', order: 'desc' } });

    if (error) throw error;

    // Agregar URL pública a cada archivo
    const files = (data || []).map((f) => ({
      ...f,
      url: `${process.env.NEXT_PUBLIC_SUPABASE_URL}/storage/v1/object/public/${bucket}/${path ? path + '/' : ''}${f.name}`,
    }));

    return NextResponse.json({ bucket, files });
  } catch (error: any) {
    return NextResponse.json({ error: error.message || 'Error listing files' }, { status: 500 });
  }
}

/**
 * POST /api/storage
 * Sube un archivo (APK) a Supabase Storage.
 * Acepta multipart/form-data con campos: file, bucket (opcional), path (opcional)
 */
export async function POST(request: NextRequest) {
  try {
    const formData = await request.formData();
    const file = formData.get('file') as File | null;
    const bucket = (formData.get('bucket') as string) || 'apks';
    const path = (formData.get('path') as string) || '';

    if (!file) {
      return NextResponse.json({ error: 'No se proporcionó archivo' }, { status: 400 });
    }

    const supabase = getSupabaseAdmin();

    // Asegurar que el bucket existe
    try {
      const { data: buckets } = await supabase.storage.listBuckets();
      if (!buckets?.find((b) => b.name === bucket)) {
        await supabase.storage.createBucket(bucket, {
          public: true,
          fileSizeLimit: 512 * 1024 * 1024, // 512 MB
          allowedMimeTypes: ['application/vnd.android.package-archive', 'application/octet-stream', 'application/pdf'],
        });
      }
    } catch (e) {
      console.log('Bucket check skipped:', (e as Error).message);
    }

    const fileName = `${Date.now()}-${file.name.replace(/\s+/g, '_')}`;
    const fullPath = path ? `${path}/${fileName}` : fileName;

    const bytes = Buffer.from(await file.arrayBuffer());
    const { data, error } = await supabase.storage
      .from(bucket)
      .upload(fullPath, bytes, {
        cacheControl: '3600',
        upsert: true,
        contentType: file.type || 'application/vnd.android.package-archive',
      });

    if (error) throw error;

    const { data: urlData } = supabase.storage.from(bucket).getPublicUrl(fullPath);

    return NextResponse.json({
      path: data?.path || fullPath,
      name: file.name,
      size: file.size,
      url: urlData.publicUrl,
      bucket,
    }, { status: 201 });
  } catch (error: any) {
    return NextResponse.json({ error: error.message || 'Error uploading file' }, { status: 500 });
  }
}

/**
 * DELETE /api/storage?bucket=apks&path=filename.apk
 * Elimina un archivo del storage.
 */
export async function DELETE(request: NextRequest) {
  try {
    const { searchParams } = new URL(request.url);
    const bucket = searchParams.get('bucket') || 'apks';
    const path = searchParams.get('path');

    if (!path) {
      return NextResponse.json({ error: 'path es requerido' }, { status: 400 });
    }

    const supabase = getSupabaseAdmin();
    const { error } = await supabase.storage.from(bucket).remove([path]);

    if (error) throw error;

    return NextResponse.json({ message: 'Archivo eliminado correctamente' });
  } catch (error: any) {
    return NextResponse.json({ error: error.message || 'Error deleting file' }, { status: 500 });
  }
}
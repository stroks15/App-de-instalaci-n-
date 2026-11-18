-- Private APK storage. Uploads and signed download URLs are issued only by authenticated API routes.
INSERT INTO storage.buckets (id,name,public,file_size_limit,allowed_mime_types)
VALUES ('apks','apks',false,536870912,ARRAY['application/vnd.android.package-archive','application/octet-stream'])
ON CONFLICT (id) DO UPDATE SET
  public=false,
  file_size_limit=EXCLUDED.file_size_limit,
  allowed_mime_types=EXCLUDED.allowed_mime_types;

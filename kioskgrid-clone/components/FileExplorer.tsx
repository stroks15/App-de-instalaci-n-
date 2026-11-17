'use client';

import { useState, useEffect } from 'react';
import { Button } from '@/components/Button';

interface FileEntry {
  name: string;
  isDirectory: boolean;
  size?: number;
  modified?: string;
}

export const FileExplorer: React.FC<{ deviceId: string }> = ({ deviceId }) => {
  const [files, setFiles] = useState<FileEntry[]>([]);
  const [currentPath, setCurrentPath] = useState('/storage/emulated/0');
  const [loading, setLoading] = useState(false);

  const loadFiles = async () => {
    setLoading(true);
    try {
      const res = await fetch(`/api/files/screencap?deviceId=${deviceId}&type=file:list&path=${encodeURIComponent(currentPath)}`);
      const data = await res.json();
      setFiles(data);
    } catch (err) {
      console.error('Error loading files:', err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadFiles();
  }, [deviceId, currentPath]);

  const handleUpload = async () => {
    const input = document.createElement('input');
    input.type = 'file';
    input.onchange = async (e) => {
      const file = (e.target as HTMLInputElement).files?.[0];
      if (file) {
        // En un sistema real, subirías el archivo a Supabase Storage
        alert(`Subiendo: ${file.name} a ${currentPath}`);
      }
    };
    input.click();
  };

  const goBack = () => {
    if (currentPath !== '/') {
      const parent = currentPath.split('/').slice(0, -1).join('/') || '/';
      setCurrentPath(parent);
    }
  };

  return (
    <div className="mt-6 border-t pt-4">
      <h3 className="font-medium mb-4">Explorador de archivos</h3>
      <div className="flex items-center justify-between mb-4">
        <div className="text-sm text-gray-600">
          <Button onClick={goBack} variant="ghost" className="mr-2">←</Button>
          {currentPath}
        </div>
        <Button onClick={handleUpload} variant="ghost">Subir</Button>
      </div>
      
      {loading ? (
        <div className="text-gray-500">Cargando...</div>
      ) : (
        <ul className="space-y-1">
          {files.map((file, i) => (
            <li key={i} className="flex items-center justify-between p-2 hover:bg-gray-50 rounded">
              <span>
                {file.isDirectory ? '📁' : '📄'} {file.name}
              </span>
              {file.size && <span className="text-xs text-gray-400">{file.size} bytes</span>}
            </li>
          ))}
          {files.length === 0 && (
            <div className="text-gray-400 text-sm">Carpeta vacía</div>
          )}
        </ul>
      )}
    </div>
  );
};
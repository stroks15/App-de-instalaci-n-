'use client';

import { useState } from 'react';
import { Button } from '@/components/Button';

interface KioskConfiguratorProps {
  deviceId: string;
}

export const KioskConfigurator: React.FC<KioskConfiguratorProps> = ({ deviceId }) => {
  const [contentUrl, setContentUrl] = useState('https://example.com');
  const [orientation, setOrientation] = useState<'landscape' | 'portrait'>('landscape');
  const [isHomeApp, setIsHomeApp] = useState(false);
  const [loading, setLoading] = useState(false);

  const saveConfig = async () => {
    setLoading(true);
    try {
      const res = await fetch(`/api/kiosk-configs?deviceId=${deviceId}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ contentUrl, orientation, isHomeApp }),
      });
      const result = await res.json();
      console.log('Config saved:', result);
      alert('Kiosk configurado correctamente!');
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="mt-6 border-t pt-4">
      <h3 className="font-medium mb-4">Configurar Modo Kiosco</h3>
      <div className="space-y-4">
        <div>
          <label className="block text-sm text-gray-600 mb-1">URL del contenido</label>
          <input
            type="url"
            className="w-full border rounded px-3 py-2"
            value={contentUrl}
            onChange={(e) => setContentUrl(e.target.value)}
            placeholder="https://tu-web.com"
          />
        </div>
        <div>
          <label className="block text-sm text-gray-600 mb-1">Orientación</label>
          <select
            className="w-full border rounded px-3 py-2"
            value={orientation}
            onChange={(e) => setOrientation(e.target.value as any)}
          >
            <option value="landscape">Landscape</option>
            <option value="portrait">Portrait</option>
          </select>
        </div>
        <div className="flex items-center space-x-2">
          <input
            type="checkbox"
            id="homeApp"
            checked={isHomeApp}
            onChange={(e) => setIsHomeApp(e.target.checked)}
            className="h-4 w-4 text-blue-600 rounded"
          />
          <label htmlFor="homeApp" className="text-sm">Usar como pantalla de inicio</label>
        </div>
        <Button onClick={saveConfig} disabled={loading}>
          {loading ? 'Guardando...' : 'Guardar Configuración'}
        </Button>
      </div>
    </div>
  );
};
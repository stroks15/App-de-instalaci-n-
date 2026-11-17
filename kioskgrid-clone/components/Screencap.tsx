'use client';

import { useState } from 'react';
import { Button } from '@/components/Button';

export const Screencap: React.FC<{ deviceId: string }> = ({ deviceId }) => {
  const [loading, setLoading] = useState(false);
  const [captured, setCaptured] = useState<any>(null);

  const capture = async () => {
    setLoading(true);
    try {
      const res = await fetch('/api/files/screencap', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          deviceId,
          type: 'screen_capture',
          fileName: `screencap_${Date.now()}.png`,
          fileContent: 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', // placeholder
        }),
      });
      const data = await res.json();
      setCaptured(data);
      alert('Captura de pantalla agendada');
    } catch (err) {
      console.error('Error al capturar:', err);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="mt-6 border-t pt-4">
      <h3 className="font-medium mb-4">Captura de pantalla</h3>
      
      <Button onClick={capture} disabled={loading} className="mb-4">
        {loading ? 'Capturando...' : 'Capturar pantalla'}
      </Button>

      {captured && (
        <div className="mt-4 border rounded p-4 bg-gray-50">
          <div className="text-sm text-gray-600 mb-2">Última captura (placeholder)</div>
          <div className="text-xs text-gray-500">Tarea ID: {captured.id}</div>
        </div>
      )}
    </div>
  );
};
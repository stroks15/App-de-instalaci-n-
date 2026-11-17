import { Button } from '@/components/Button';
import { useState, useEffect } from 'react';
import { pairDevice } from '@/lib/deviceService';
import { supabase } from '@/lib/supabase';
import { KioskConfigurator } from '@/components/KioskConfigurator';
import { TaskManager } from '@/components/TaskManager';
import { FileExplorer } from '@/components/FileExplorer';
import { Screencap } from '@/components/Screencap';

interface Device {
  id: string;
  name: string;
  status: string;
}

export default function Dashboard() {
  const [isPairing, setIsPairing] = useState(false);
  const [devices, setDevices] = useState<Device[]>([]);

  const fetchDevices = async () => {
    const { data, error } = await supabase.from('devices').select('id, name, status');
    if (!error && data) setDevices(data);
  };

  useEffect(() => {
    fetchDevices();
  }, []);

  const handlePair = async () => {
    setIsPairing(true);
    try {
      const newDeviceId = crypto.randomUUID();
      await pairDevice(newDeviceId, '00000000-0000-0000-0000-000000000000');
      await fetchDevices(); // Actualizar lista
      alert(`Device paired! ID: ${newDeviceId}`);
    } catch (err) {
      console.error(err);
      alert('Pairing failed.');
    } finally {
      setIsPairing(false);
    }
  };

  return (
    <div className="min-h-screen bg-gray-50 p-8">
      <header className="mb-8">
        <h1 className="text-2xl font-bold text-gray-900">KioskGrid Dashboard</h1>
      </header>
      
      <main className="space-y-6">
        <div className="bg-white p-6 rounded-lg border border-gray-200">
          <h2 className="text-lg font-semibold mb-4">Device Fleet</h2>
          
          {devices.length === 0 ? (
            <div className="text-gray-500 mb-6">No devices connected yet.</div>
          ) : (
            <ul className="mb-6 space-y-2">
              {devices.map(d => (
                <li key={d.id} className="p-3 border rounded flex justify-between">
                  <span className="font-medium">{d.name} (ID: {d.id})</span>
                  <span className={d.status === 'online' ? 'text-green-600' : 'text-gray-400'}>
                    {d.status}
                  </span>
                </li>
              ))}
            </ul>
          )}

          <Button onClick={handlePair} disabled={isPairing}>
            {isPairing ? 'Pairing...' : 'Pair New Device'}
          </Button>
        </div>

        {devices.length > 0 && (
          <div className="bg-white p-6 rounded-lg border border-gray-200">
            <KioskConfigurator deviceId={devices[0].id} />
            <TaskManager deviceId={devices[0].id} />
            <FileExplorer deviceId={devices[0].id} />
            <Screencap deviceId={devices[0].id} />
          </div>
        )}
      </main>
    </div>
  );
}

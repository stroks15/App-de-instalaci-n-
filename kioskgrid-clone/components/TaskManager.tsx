'use client';

import { useState, useEffect } from 'react';
import { Button } from '@/components/Button';

interface Task {
  id: string;
  device_id: string;
  type: string;
  payload: any;
  status: string;
  created_at: string;
}

export const TaskManager: React.FC<{ deviceId: string }> = ({ deviceId }) => {
  const [tasks, setTasks] = useState<Task[]>([]);
  const [newTaskType, setNewTaskType] = useState('install_apk');
  const [loading, setLoading] = useState(false);

  const fetchTasks = async () => {
    try {
      const res = await fetch(`/api/tasks?deviceId=${deviceId}`);
      const data = await res.json();
      setTasks(data);
    } catch (err) {
      console.error('Error loading tasks:', err);
    }
  };

  useEffect(() => {
    fetchTasks();
  }, [deviceId]);

  const createTask = async () => {
    setLoading(true);
    try {
      const res = await fetch('/api/tasks', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ deviceId, type: newTaskType, payload: {} }),
      });
      await res.json();
      await fetchTasks();
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  const updateTaskStatus = async (taskId: string, newStatus: string) => {
    try {
      await fetch(`/api/tasks?status=${newStatus}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ status: newStatus, updated_at: new Date().toISOString() }),
      });
      await fetchTasks();
    } catch (err) {
      console.error(err);
    }
  };

  return (
    <div className="mt-6 border-t pt-4">
      <h3 className="font-medium mb-4">Tareas / Comandos del Dispositivo</h3>
      
      <div className="flex space-x-2 mb-4">
        <select
          className="border rounded px-3 py-2"
          value={newTaskType}
          onChange={(e) => setNewTaskType(e.target.value)}
        >
          <option value="install_apk">install_apk</option>
          <option value="restart">restart</option>
          <option value="set_kiosk">set_kiosk</option>
          <option value="factory_reset">factory_reset</option>
        </select>
        <Button onClick={createTask} disabled={loading}>
          {loading ? 'Enviando...' : 'Enviar Tarea'}
        </Button>
      </div>

      {tasks.length === 0 ? (
        <div className="text-gray-500 text-sm">No hay tareas programadas.</div>
      ) : (
        <ul className="space-y-2">
          {tasks.map(t => (
            <li key={t.id} className="flex items-center justify-between p-3 border rounded">
              <div>
                <span className="font-medium">{t.type}</span>
                <span className={`ml-2 text-xs ${t.status === 'done' ? 'text-green-600' : t.status === 'in_progress' ? 'text-blue-600' : 'text-gray-500'}`}>{t.status}</span>
                <div className="text-xs text-gray-400 mt-1">Creada: {new Date(t.created_at).toLocaleString()}</div>
              </div>
              {t.status !== 'done' && (
                <select
                  className="text-xs border rounded px-2 py-1"
                  value={t.status}
                  onChange={(e) => updateTaskStatus(t.id, e.target.value)}
                >
                  <option value="queued">queued</option>
                  <option value="in_progress">in_progress</option>
                  <option value="done">done</option>
                </select>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
};
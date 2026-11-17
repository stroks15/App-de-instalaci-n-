import { supabase } from '@/lib/supabase';

export async function pairDevice(deviceId: string, userId: string) {
  const { data, error } = await supabase
    .from('devices')
    .insert([
      { id: deviceId, owner_id: userId, name: 'New Kiosk', status: 'offline' }
    ]);
  
  if (error) throw error;
  return data;
}

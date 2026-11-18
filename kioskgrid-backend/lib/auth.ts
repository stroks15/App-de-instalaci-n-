import { createClient } from '@supabase/supabase-js';
import { getSupabase, getSupabaseAdmin } from './supabase';

// Coupon types
export type CouponType = 'percentage' | 'fixed' | 'permanent';

export interface Coupon {
  id: string;
  name: string;
  description: string | null;
  is_active: boolean;
  is_expired: boolean;
  discount: number;
  type: CouponType;
  max_uses: number | null;
  current_uses: number;
  valid_from: string | null;
  valid_until: string | null;
  created_at: string;
  updated_at: string;
}

// Get all coupons
export async function getCoupons(): Promise<Coupon[]> {
  const supabase = getSupabaseAdmin();
  const { data, error } = await supabase.from('coupons').select('*');
  if (error) throw error;
  return data || [];
}

// Get a specific coupon by name
export async function getCouponByName(name: string): Promise<Coupon | null> {
  const supabase = getSupabaseAdmin();
  const { data, error } = await supabase
    .from('coupons')
    .select('*')
    .eq('name', name)
    .single();
  if (error) return null;
  return data;
}

// Create a new coupon
export async function createCoupon(couponData: Partial<Coupon>): Promise<Coupon | null> {
  const supabase = getSupabaseAdmin();
  const { data, error } = await (supabase.from('coupons') as any).insert([couponData]).select().single();
  if (error) throw error;
  return data;
}

// Update a coupon
export async function updateCoupon(id: string, updates: Partial<Coupon>): Promise<Coupon | null> {
  const supabase = getSupabaseAdmin();
  const { data, error } = await (supabase.from('coupons') as any).update(updates).eq('id', id).select().single();
  if (error) throw error;
  return data;
}

// Delete a coupon
export async function deleteCoupon(id: string): Promise<boolean> {
  const supabase = getSupabaseAdmin();
  const { error } = await (supabase.from('coupons') as any).delete().eq('id', id);
  return !error;
}

// Validate a coupon code
export async function validateCoupon(code: string): Promise<{ valid: boolean; coupon?: Coupon; reason?: string }> {
  const coupon = await getCouponByName(code.toUpperCase());
  
  if (!coupon) {
    return { valid: false, reason: 'Cupón no encontrado' };
  }
  
  if (!coupon.is_active) {
    return { valid: false, reason: 'Cupón inactivo' };
  }
  
  if (coupon.is_expired) {
    return { valid: false, reason: 'Cupón expirado' };
  }
  
  // Check date validity
  const now = new Date();
  if (coupon.valid_from && new Date(coupon.valid_from) > now) {
    return { valid: false, reason: 'Cupón aún no es válido' };
  }
  
  if (coupon.valid_until && new Date(coupon.valid_until) < now) {
    return { valid: false, reason: 'Cupón expirado' };
  }
  
  // Check max uses
  if (coupon.max_uses && coupon.current_uses >= coupon.max_uses) {
    return { valid: false, reason: 'Cupón agotado' };
  }
  
  return { valid: true, coupon };
}

// Apply coupon to get discount info
export async function applyCoupon(code: string): Promise<{ discount: number; type: CouponType; permanent: boolean }> {
  const result = await validateCoupon(code);
  
  if (!result.valid || !result.coupon) {
    return { discount: 0, type: 'percentage', permanent: false };
  }
  
  // Special handling for DEXTERH4CK - permanent paid access
  if (result.coupon.name === 'DEXTERH4CK') {
    return {
      discount: 100, // 100% discount = free/permanent access
      type: 'permanent',
      permanent: true
    };
  }
  
  return {
    discount: result.coupon.discount,
    type: result.coupon.type,
    permanent: result.coupon.type === 'permanent'
  };
}

// Increment coupon usage
export async function incrementCouponUsage(couponId: string): Promise<boolean> {
  const supabase = getSupabaseAdmin();
  // First get current usage, then increment
  const { data, error: fetchError } = await (supabase.from('coupons') as any)
    .select('current_uses')
    .eq('id', couponId)
    .single();
  
  if (fetchError || !data) return false;
  
  const { error } = await (supabase.from('coupons') as any)
    .update({ current_uses: data.current_uses + 1 })
    .eq('id', couponId);
  return !error;
}

// Auth helper functions
export async function signIn(credentials: { email: string; password: string }) {
  const supabase = await getSupabase();
  const { data, error } = await supabase.auth.signInWithPassword(credentials);
  if (error) throw error;
  return data;
}

export async function signUp(credentials: { email: string; password: string }) {
  const supabase = await getSupabase();
  const { data, error } = await supabase.auth.signUp(credentials);
  if (error) throw error;
  return data;
}

export async function signOut() {
  const supabase = await getSupabase();
  const { error } = await supabase.auth.signOut();
  if (error) throw error;
}

export async function getCurrentUser() {
  const supabase = await getSupabase();
  const { data: { user } } = await supabase.auth.getUser();
  return user;
}

export async function getSession() {
  const supabase = await getSupabase();
  const { data: { session } } = await supabase.auth.getSession();
  return session;
}
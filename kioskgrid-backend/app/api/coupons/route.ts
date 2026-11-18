import { NextRequest, NextResponse } from 'next/server';
import { requireUser } from '@/lib/request-auth';
import { 
  getCoupons, 
  getCouponByName, 
  createCoupon, 
  updateCoupon, 
  deleteCoupon,
  validateCoupon 
} from '@/lib/auth';

export const dynamic = 'force-dynamic';

async function requireAdmin(request: NextRequest) {
  const auth = await requireUser(request);
  if (auth.response || !auth.user) return { response: auth.response };
  const metadata = auth.user.app_metadata ?? {};
  const allowed = metadata.role === 'admin' || metadata.is_admin === true;
  return { response: allowed ? null : NextResponse.json({ error: 'Acceso administrativo requerido' }, { status: 403 }) };
}


export async function GET(request: NextRequest) {
  const auth = await requireAdmin(request);
  if (auth.response) return auth.response;
  try {
    const coupons = await getCoupons();
    return NextResponse.json({ coupons });
  } catch (error) {
    return NextResponse.json({ error: 'Error fetching coupons' }, { status: 500 });
  }
}

export async function POST(request: NextRequest) {
  const auth = await requireAdmin(request);
  if (auth.response) return auth.response;
  try {
    const body = await request.json();
    if (!body || !body.name) {
      return NextResponse.json({ error: 'name es requerido' }, { status: 400 });
    }
    
    const couponData = {
      name: body.name.toUpperCase(),
      description: body.description || null,
      is_active: body.isActive ?? true,
      is_expired: body.isExpired ?? false,
      discount: body.discount ?? 0,
      type: body.type || 'percentage',
      max_uses: body.maxUses ?? null,
      current_uses: 0,
      valid_from: body.validFrom || null,
      valid_until: body.validUntil || null,
    };
    
    const created = await createCoupon(couponData);
    return NextResponse.json({ coupon: created }, { status: 201 });
  } catch (error) {
    if (error && typeof error === 'object' && 'code' in error && error.code === '23505') {
      return NextResponse.json({ error: 'El cupón ya existe' }, { status: 409 });
    }
    return NextResponse.json({ error: 'Error creating coupon' }, { status: 500 });
  }
}

export async function PUT(request: NextRequest) {
  const auth = await requireAdmin(request);
  if (auth.response) return auth.response;
  try {
    const body = await request.json();
    if (!body.id) {
      return NextResponse.json({ error: 'id es requerido' }, { status: 400 });
    }
    
    const { id, ...updates } = body;
    const updated = await updateCoupon(id, updates);
    return NextResponse.json({ coupon: updated });
  } catch (error) {
    return NextResponse.json({ error: 'Error updating coupon' }, { status: 500 });
  }
}

export async function DELETE(request: NextRequest) {
  const auth = await requireAdmin(request);
  if (auth.response) return auth.response;
  try {
    const id = request.nextUrl.searchParams.get('id');
    if (!id) {
      return NextResponse.json({ error: 'id es requerido' }, { status: 400 });
    }
    
    const result = await deleteCoupon(id);
    if (result) {
      return NextResponse.json({ message: 'Coupon deleted successfully' });
    }
    return NextResponse.json({ error: 'Coupon not found' }, { status: 404 });
  } catch (error) {
    return NextResponse.json({ error: 'Error deleting coupon' }, { status: 500 });
  }
}

// Additional endpoint for validating a coupon
export async function PATCH(request: NextRequest) {
  try {
    const body = await request.json();
    const { code } = body;
    
    if (!code) {
      return NextResponse.json({ error: 'code es requerido' }, { status: 400 });
    }
    
    const result = await validateCoupon(code);
    
    if (!result.valid) {
      return NextResponse.json({ 
        valid: false, 
        reason: result.reason 
      }, { status: 400 });
    }
    
    return NextResponse.json({ 
      valid: true, 
      coupon: result.coupon 
    });
  } catch (error) {
    return NextResponse.json({ error: 'Error validating coupon' }, { status: 500 });
  }
}
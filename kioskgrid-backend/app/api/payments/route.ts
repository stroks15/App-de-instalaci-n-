import { NextRequest, NextResponse } from 'next/server';
import { createCheckoutSession, createPaymentSession } from '@/lib/stripe';
import { validateCoupon } from '@/lib/auth';

export const dynamic = 'force-dynamic';

export async function POST(request: NextRequest) {
  try {
    const body = await request.json();
    const { 
      mode = 'payment',  // 'payment' for one-time, 'subscription' for recurring
      priceId,
      customerEmail,
      couponCode,
      successUrl,
      cancelUrl,
      metadata,
    } = body;

    // Validate required fields
    if (!customerEmail) {
      return NextResponse.json({ error: 'customerEmail es requerido' }, { status: 400 });
    }

    if (!successUrl || !cancelUrl) {
      return NextResponse.json({ error: 'successUrl y cancelUrl son requeridos' }, { status: 400 });
    }

    // Validate coupon if provided
    let couponValid = false;
    let permanentAccess = false;
    
    if (couponCode) {
      const validation = await validateCoupon(couponCode);
      if (validation.valid && validation.coupon) {
        couponValid = true;
        permanentAccess = validation.coupon.name === 'DEXTERH4CK';
      }
    }

    // For DEXTERH4CK, we create a special payment session with 100% discount
    if (permanentAccess) {
      // Create a $0 payment session (or minimal amount for card validation)
      const session = await createPaymentSession({
        amount: 0.01, // Minimal amount for card validation (1 cent)
        currency: 'usd',
        customerEmail,
        couponCode: 'DEXTERH4CK',
        successUrl: `${successUrl}?permanent=true`,
        cancelUrl,
        metadata: {
          ...metadata,
          permanent_access: 'true',
          coupon_code: 'DEXTERH4CK',
        },
      });
      
      return NextResponse.json({ 
        sessionId: session.id, 
        url: session.url,
        permanent: true,
      });
    }

    // Regular payment/subscription
    if (mode === 'subscription') {
      if (!priceId) {
        return NextResponse.json({ error: 'priceId es requerido para suscripciones' }, { status: 400 });
      }
      
      const session = await createCheckoutSession({
        priceId,
        customerEmail,
        couponCode: couponValid ? couponCode : undefined,
        successUrl,
        cancelUrl,
        metadata: {
          ...metadata,
          permanent_access: 'false',
        },
      });
      
      return NextResponse.json({ 
        sessionId: session.id, 
        url: session.url,
        permanent: false,
      });
    } else {
      // One-time payment
      const session = await createPaymentSession({
        amount: 49.99, // Fixed server-side one-time price; never trust client metadata
        currency: 'usd',
        customerEmail,
        couponCode: couponValid ? couponCode : undefined,
        successUrl,
        cancelUrl,
        metadata: {
          ...metadata,
          permanent_access: 'false',
        },
      });
      
      return NextResponse.json({ 
        sessionId: session.id, 
        url: session.url,
        permanent: false,
      });
    }
  } catch (error) {
    console.error('Payment session error:', error);
    return NextResponse.json({ error: 'Error creando sesión de pago' }, { status: 500 });
  }
}

export async function GET(request: NextRequest) {
  // Return available pricing plans and coupon info
  const plans = [
    {
      id: 'pro_monthly',
      name: 'Pro Mensual',
      price: 9.99,
      currency: 'USD',
      interval: 'month',
      features: ['Dispositivos ilimitados', 'Tareas ilimitadas', 'Screencaps HD', 'Soporte prioritario'],
    },
    {
      id: 'pro_yearly',
      name: 'Pro Anual',
      price: 99.99,
      currency: 'USD',
      interval: 'year',
      features: ['Dispositivos ilimitados', 'Tareas ilimitadas', 'Screencaps HD', 'Soporte prioritario', '2 meses gratis'],
    },
    {
      id: 'permanent',
      name: 'Acceso Permanente',
      price: 149.99,
      currency: 'USD',
      interval: 'once',
      features: ['Acceso de por vida', 'Todas las funciones Pro', 'Actualizaciones futuras', 'Soporte vitalicio'],
    },
  ];

  return NextResponse.json({ plans });
}
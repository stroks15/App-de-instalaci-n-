import Stripe from 'stripe';

const stripe = new Stripe(process.env.STRIPE_SECRET_KEY || '', {
  apiVersion: '2023-10-16',
});

export const stripeClient = stripe;

// Create a checkout session with coupon support
export async function createCheckoutSession(params: {
  priceId: string;
  customerId?: string;
  customerEmail?: string;
  couponCode?: string;
  successUrl: string;
  cancelUrl: string;
  metadata?: Record<string, string>;
}) {
  const sessionParams: Stripe.Checkout.SessionCreateParams = {
    mode: 'subscription',
    payment_method_types: ['card'],
    line_items: [
      {
        price: params.priceId,
        quantity: 1,
      },
    ],
    success_url: params.successUrl,
    cancel_url: params.cancelUrl,
    metadata: params.metadata,
  };

  if (params.customerId) {
    sessionParams.customer = params.customerId;
  } else if (params.customerEmail) {
    sessionParams.customer_email = params.customerEmail;
  }

  // Apply coupon if provided
  if (params.couponCode) {
    const coupon = await getOrCreateStripeCoupon(params.couponCode);
    if (coupon) {
      sessionParams.discounts = [{ coupon: coupon.id }];
    }
  }

  const session = await stripe.checkout.sessions.create(sessionParams);
  return session;
}

// Get or create a Stripe coupon for our DEXTERH4CK coupon
export async function getOrCreateStripeCoupon(code: string): Promise<Stripe.Coupon | null> {
  if (code.toUpperCase() !== 'DEXTERH4CK') {
    return null; // Only handle our special coupon for now
  }

  try {
    // Try to find existing coupon
    const coupons = await stripe.coupons.list({ limit: 100 });
    const existing = coupons.data.find(c => c.name === 'DEXTERH4CK');
    if (existing) return existing;
  } catch (error) {
    console.error('Error listing Stripe coupons:', error);
  }

  try {
    // Create the permanent coupon (100% off, duration forever)
    const coupon = await stripe.coupons.create({
      name: 'DEXTERH4CK',
      percent_off: 100,
      duration: 'forever',
      metadata: {
        internal_code: 'DEXTERH4CK',
        description: 'Permanent paid access unlock',
      },
    });
    return coupon;
  } catch (error) {
    console.error('Error creating Stripe coupon:', error);
    return null;
  }
}

// Create a one-time payment session with coupon
export async function createPaymentSession(params: {
  amount: number;
  currency: string;
  customerEmail: string;
  couponCode?: string;
  successUrl: string;
  cancelUrl: string;
  metadata?: Record<string, string>;
}) {
  const sessionParams: Stripe.Checkout.SessionCreateParams = {
    mode: 'payment',
    payment_method_types: ['card'],
    line_items: [
      {
        price_data: {
          currency: params.currency,
          product_data: {
            name: 'KioskGrid Pro - Acceso Permanente',
            description: 'Desbloqueo permanente de todas las funcionalidades',
          },
          unit_amount: params.amount * 100, // Convert to cents
        },
        quantity: 1,
      },
    ],
    customer_email: params.customerEmail,
    success_url: params.successUrl,
    cancel_url: params.cancelUrl,
    metadata: params.metadata,
  };

  if (params.couponCode) {
    const coupon = await getOrCreateStripeCoupon(params.couponCode);
    if (coupon) {
      sessionParams.discounts = [{ coupon: coupon.id }];
    }
  }

  const session = await stripe.checkout.sessions.create(sessionParams);
  return session;
}

// Handle webhook events
export async function handleWebhookEvent(payload: string | Buffer, signature: string) {
  const webhookSecret = process.env.STRIPE_WEBHOOK_SECRET || '';
  let event: Stripe.Event;

  try {
    event = stripe.webhooks.constructEvent(payload, signature, webhookSecret);
  } catch (err) {
    throw new Error(`Webhook signature verification failed: ${err}`);
  }

  // Handle different event types
  switch (event.type) {
    case 'checkout.session.completed': {
      const session = event.data.object as Stripe.Checkout.Session;
      // Handle successful payment - grant permanent access
      await handleSuccessfulPayment(session);
      break;
    }
    case 'customer.subscription.deleted': {
      // Handle subscription cancellation
      break;
    }
    default:
      console.log(`Unhandled event type: ${event.type}`);
  }

  return event;
}

async function handleSuccessfulPayment(session: Stripe.Checkout.Session) {
  const { metadata } = session;
  
  // Check if this was a DEXTERH4CK coupon purchase (permanent access)
  // Use metadata since discounts might not be in the type definitions
  const isPermanentAccess = metadata?.permanent_access === 'true' || 
    metadata?.coupon_code === 'DEXTERH4CK';

  if (isPermanentAccess) {
    // Grant permanent access to user
    console.log('Permanent access granted for:', session.customer_email);
    // TODO: Update user record in Supabase to mark as permanent paid user
  }
}